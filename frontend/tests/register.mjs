// 使用项目现有 TypeScript 和 Node 测试器，不增加测试框架依赖。
import { registerHooks } from 'node:module'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === 'element-plus') return { url: new URL('./messages.mjs', import.meta.url).href, shortCircuit: true }
    if (specifier.startsWith('@/')) {
      const name = specifier.slice(2)
      return { url: new URL(`../src/${name === 'api' ? 'api/index' : name}.ts`, import.meta.url).href, shortCircuit: true }
    }
    return nextResolve(specifier, context)
  },
  load(url, context, nextLoad) {
    if (url.endsWith('.ts')) return {
      format: 'module', shortCircuit: true,
      source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
        compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
      }).outputText,
    }
    return nextLoad(url, context)
  },
})
