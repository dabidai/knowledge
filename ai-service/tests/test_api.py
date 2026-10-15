"""使用替身模型验证 HTTP 契约与调度，不下载模型、不连接数据库/Ollama。"""
import asyncio
import math
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import httpx
from langchain_core.messages import AIMessage, HumanMessage

import main


class APITests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        main.app.state.embedding_gate = asyncio.Semaphore(1)
        main.app.state.generation_gate = asyncio.Semaphore(1)
        main.app.state.inflight = set()
        self.vector = [0.01] * main.cfg.EMBEDDING_DIMENSIONS
        self.embedder = Mock()
        self.embedder.encode.return_value.tolist.return_value = [self.vector]
        self.llm = SimpleNamespace(ainvoke=AsyncMock(return_value=AIMessage(content='回答 {ok}')))
        self.models = patch.multiple(main, embedder=self.embedder, llm=self.llm, actual_backend="pytorch")
        self.models.start()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://test")

    async def asyncTearDown(self):
        await asyncio.gather(*main.app.state.inflight, return_exceptions=True)
        await self.client.aclose()
        self.models.stop()

    async def test_braces_in_context_question_and_history_are_literal(self):
        request = {"question": '解释 {budget}', "contexts": ['JSON: {"a": 1}'],
                   "history": [{"role": "assistant", "content": "历史 {value}"}]}
        for path in ("/chat", "/ask"):
            with self.subTest(path=path):
                response = await self.client.post(path, json=request)
                self.assertEqual(200, response.status_code)
                messages = self.llm.ainvoke.call_args.args[0]
                self.assertIsInstance(messages[-1], HumanMessage)
                self.assertIn('{budget}', messages[-1].content)
                self.assertIn('{"a": 1}', messages[-1].content)
                self.assertIn('{value}', messages[1].content)

    async def test_large_structured_context_and_history_fit_budget(self):
        request = {"question": "由谁签阅？", "contexts": ['\n【相关事项】\n' + '签阅意见' * 20000],
                   "history": [{"role": "user", "content": '历史' * 2000}] * 10}
        response = await self.client.post("/chat", json=request)
        self.assertEqual(200, response.status_code)
        self.assertTrue(response.json()['input_truncated'])
        messages = self.llm.ainvoke.call_args.args[0]
        estimated = sum(len(m.content.encode('utf-8')) + 32 for m in messages)
        self.assertLessEqual(estimated, main.cfg.OLLAMA_NUM_CTX - main.cfg.OLLAMA_NUM_PREDICT - 256)

    async def test_invalid_inputs_do_not_reach_models(self):
        cases = [("/embed", {"text": " "}),
                 ("/embed", {"text": "x" * 2049}),
                 ("/embed-batch", {"texts": []}),
                 ("/embed-batch", {"texts": ["a"] * 101}),
                 ("/embed-batch", {"texts": ["a" * 1000] * 61}),
                 ("/chat", {"question": "q" * 1001, "contexts": []}),
                 ("/chat", {"question": "q", "contexts": ["x"] * 21}),
                 ("/chat", {"question": "q", "contexts": ["x" * 100001]}),
                 ("/chat", {"question": "q", "contexts": ["x" * 100000] * 3}),
                 ("/chat", {"question": "q", "contexts": [], "history": [{"role": "system", "content": "x"}]}),
                 ("/chat", {"question": "q", "contexts": [], "history": [{"role": "user", "content": "x"}] * 21})]
        for path, body in cases:
            with self.subTest(path=path, keys=list(body)):
                response = await self.client.post(path, json=body)
                self.assertEqual(422, response.status_code)
        self.embedder.encode.assert_not_called()
        self.llm.ainvoke.assert_not_awaited()

    async def test_body_limit_handles_chunked_requests(self):
        async def body():
            yield b'{"text":"'
            yield b'x' * main.cfg.MAX_BODY_BYTES
            yield b'"}'
        response = await self.client.post("/embed", content=body())
        self.assertEqual(413, response.status_code)
        self.embedder.encode.assert_not_called()

    async def test_vectors_are_validated(self):
        for vectors in ([], [[1.0]], [[math.nan] * len(self.vector)], [[math.inf] * len(self.vector)]):
            self.embedder.encode.return_value.tolist.return_value = vectors
            response = await self.client.post("/embed-batch", json={"texts": ["a"]})
            self.assertEqual(500, response.status_code)
            self.assertEqual("Embedding 生成失败", response.json()['detail'])

    async def test_batch_preserves_order_and_count(self):
        second = [0.02] * len(self.vector)
        self.embedder.encode.return_value.tolist.return_value = [self.vector, second]
        response = await self.client.post("/embed-batch", json={"texts": ["first", "second"]})
        self.assertEqual([self.vector, second], response.json()['embeddings'])

    async def test_generation_failure_and_timeout_are_not_answers(self):
        for error, expected in ((RuntimeError("secret path"), 502), (TimeoutError(), 504)):
            self.llm.ainvoke.side_effect = error
            response = await self.client.post("/chat", json={"question": "q", "contexts": []})
            self.assertEqual(expected, response.status_code)
            self.assertNotIn("answer", response.json())
            self.assertNotIn("secret path", response.text)

    async def test_empty_generation_is_failure(self):
        self.llm.ainvoke.return_value = AIMessage(content=" ")
        response = await self.client.post("/chat", json={"question": "q", "contexts": []})
        self.assertEqual(502, response.status_code)

    async def test_embedding_does_not_block_health_and_busy_is_rejected(self):
        started, release = threading.Event(), threading.Event()

        def slow(*args, **kwargs):
            started.set()
            if not release.wait(5):
                raise RuntimeError("test release timeout")
            return SimpleNamespace(tolist=lambda: [self.vector])

        self.embedder.encode.side_effect = slow
        first = asyncio.create_task(self.client.post("/embed", json={"text": "a"}))
        try:
            self.assertTrue(await asyncio.to_thread(started.wait, 2))
            response = await asyncio.wait_for(self.client.get("/health/live"), 1)
            self.assertEqual(200, response.status_code)
            second = await asyncio.wait_for(self.client.post("/embed", json={"text": "b"}), 1)
            self.assertEqual(503, second.status_code)
            self.assertEqual("2", second.headers['retry-after'])
            first.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await first
            # 原线程仍在运行，不能因为调用者取消就放入下一批。
            third = await self.client.post("/embed", json={"text": "c"})
            self.assertEqual(503, third.status_code)
        finally:
            release.set()
            await asyncio.gather(first, *main.app.state.inflight, return_exceptions=True)
        response = await self.client.post("/embed", json={"text": "d"})
        self.assertEqual(200, response.status_code)

    async def test_generation_limit_leaves_embedding_available(self):
        started, release = asyncio.Event(), asyncio.Event()

        async def slow(messages):
            started.set()
            await release.wait()
            return AIMessage(content="ok")

        self.llm.ainvoke.side_effect = slow
        first = asyncio.create_task(self.client.post("/chat", json={"question": "q", "contexts": []}))
        try:
            await asyncio.wait_for(started.wait(), 1)
            response = await self.client.post("/ask", json={"question": "q", "contexts": []})
            self.assertEqual(503, response.status_code)
            self.assertEqual(200, (await self.client.post("/embed", json={"text": "a"})).status_code)
        finally:
            release.set()
            await first

    async def test_readiness_checks_model_without_generating(self):
        for models, expected in (([{"name": main.cfg.OLLAMA_MODEL}], 200), ([], 503)):
            upstream = Mock()
            upstream.raise_for_status.return_value = None
            upstream.json.return_value = {"models": models}
            client = AsyncMock()
            client.get.return_value = upstream
            with patch.object(main.httpx, "AsyncClient") as factory:
                factory.return_value.__aenter__.return_value = client
                response = await main.readiness()
                self.assertEqual(expected, response.status_code)
        self.llm.ainvoke.assert_not_awaited()
        self.embedder.encode.assert_not_called()

    async def test_ollama_down_keeps_live_and_embedding_working(self):
        with patch.object(main.httpx, "AsyncClient") as factory:
            factory.return_value.__aenter__.side_effect = httpx.ConnectError("offline")
            self.assertEqual(503, (await main.readiness()).status_code)
        self.assertEqual(200, (await self.client.get("/health/live")).status_code)
        self.assertEqual(200, (await self.client.post("/embed", json={"text": "a"})).status_code)

    async def test_lifespan_checks_dimensions_and_resets_on_shutdown(self):
        self.embedder.get_sentence_embedding_dimension.return_value = main.cfg.EMBEDDING_DIMENSIONS
        with patch.object(main, "load_embedder", return_value=self.embedder), patch.object(main, "ChatOllama"):
            async with main.lifespan(main.app):
                self.assertEqual(main.cfg.EMBEDDING_BACKEND, main.actual_backend)
            self.assertIsNone(main.embedder)
        self.embedder.get_sentence_embedding_dimension.return_value = 7
        with patch.object(main, "load_embedder", return_value=self.embedder):
            with self.assertRaises(ValueError):
                async with main.lifespan(main.app):
                    pass
        self.assertIsNone(main.embedder)


if __name__ == "__main__":
    unittest.main()
