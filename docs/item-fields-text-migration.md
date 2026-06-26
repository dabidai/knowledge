# items 表字段从 varchar(500) 迁移到 TEXT

## 问题现象

在 `receive` 目录导入 CSV 时，报错：
```
导入失败: could not execute statement [ERROR: value too long for type character varying(500)]
[insert into items (category,category_no,...,ref_no,...,item_id) values (?,?,...,?)]
```

注意：这次用 `RFC4180Parser` 解析，**没有出现列数异常的 warn 日志**（上一次 `meeting/内部工作批示单` 目录导入时 openCSV 默认解析器导致大量列数异常）。

---

## 排查过程

### 关键问题：如何区分"解析异常"还是"单纯长度不够"

上一次（`meeting/内部工作批示单`）的反复超长错误根因是 openCSV 默认解析器与 Python csv 模块行为不一致，数据跨行串位导致字段内容全是拼接出的垃圾文本。这次已经换用 `RFC4180Parser`，但 `receive` 目录的 CSV 文件之前没测试过，需要先确认解析是否正常。

**诊断方法：用 Python csv 模块检查源文件各列最大长度和列数异常**

```bash
cd ~/llm/docker/anythingllm/input/receive && python3 << 'PYEOF'
import csv, os

for fname in sorted(os.listdir('.')):
    if not fname.endswith('.csv'):
        continue
    fpath = os.path.join('.', fname)
    with open(fpath, 'rb') as fb:
        raw = fb.read()
    for enc in ['utf-8', 'gb18030']:
        try:
            text = raw.decode(enc)
            break
        except:
            continue

    reader = csv.reader(text.splitlines())
    header = next(reader, [])
    print(f"\n=== {fname} (编码: {enc}) ===")
    print(f"表头({len(header)}列): {header[:10]}")

    max_lens = {}
    bad_rows = []
    for i, row in enumerate(reader, start=2):
        if len(row) != len(header):
            bad_rows.append((i, len(row), row[0][:80] if row else ''))
        for j, val in enumerate(row):
            col = header[j] if j < len(header) else f'col{j}'
            max_lens[col] = max(max_lens.get(col, 0), len(val))

    print(f"总行数: {i-1}, 列数异常行: {len(bad_rows)}")
    if bad_rows[:5]:
        for br in bad_rows[:5]:
            print(f"  行{br[0]}: {br[1]}列, row[0]={br[2]}")
    print("各列最大长度:")
    for col, ml in sorted(max_lens.items(), key=lambda x: -x[1]):
        flag = " *** 超过500!" if ml > 500 else ""
        print(f"  {col}: {ml}{flag}")
PYEOF
```

### 诊断结果

```
=== file_index.csv (编码: utf-8) ===
表头(4列): ['文件ID', '文件名', '事项标题', '事项ID']
总行数: 27664, 列数异常行: 0
各列最大长度:
  事项标题: 644 *** 超过500!
  文件名: 117
  文件ID: 18
  事项ID: 8

=== item.csv (编码: utf-8) ===
表头(9列): ['事项ID', '事项标题', '事项发起时间', '事项分类', '分类编号', '年度', '字号', '发文单位', '事项类型']
总行数: 19992, 列数异常行: 0
各列最大长度:
  字号: 2044 *** 超过500!
  事项标题: 644 *** 超过500!
  分类编号: 75
  发文单位: 70
  事项ID: 32
  事项分类: 30
  事项发起时间: 16
  年度: 4
  事项类型: 2

=== item_with_opinions.csv (编码: utf-8) ===
表头(5列): ['事项ID', '事项标题', '签阅时间', '签阅人', '签阅意见']
总行数: 94915, 列数异常行: 0
各列最大长度:
  签阅意见: 1743 *** 超过500!
  事项标题: 644 *** 超过500!
  签阅时间: 16
  签阅人: 12
  事项ID: 8
```

### 结论：不是解析异常，是单纯长度不够

- **三个 CSV 文件列数异常行均为 0** — `RFC4180Parser` 解析完全正常
- `字号`（ref_no）最长 2044 字符，远超数据库 varchar(500) — **这是本次报错的直接原因**
- `事项标题`（title）最长 644，但此前已扩大到 varchar(2000)，通过
- `签阅意见`（content）最长 1743，Opinion 表已改为 TEXT，通过
- `分类编号`（category_no）最长 75、`发文单位`（issuer）最长 70、`事项分类`（category）最长 30 — 暂时安全

---

## 与上次"value too long"问题的对比

| 维度 | 上次（meeting/内部工作批示单） | 本次（receive） |
|------|------|------|
| **根因** | openCSV 默认解析器用 `\` 转义，与 RFC 4180 不一致，数据串位 | 字段长度 varchar(500) 无法容纳合法长数据 |
| **诊断关键信号** | 列数异常行大量出现（10-36 列不等） | 列数异常行 = 0 |
| **错误轮换** | 每次加大字段后换另一个字段报错 | 只报 varchar(500)，对应 ref_no |
| **解决** | 替换解析器为 `RFC4180Parser` | 字段从 varchar(500) 改为 TEXT |
| **Python 诊断** | Python 解析正常但 Java 解析错乱 → 解析器差异 | Python 和 Java 解析都正常 → 就是数据长 |

---

## 最终解决方案

### 数据库

```sql
ALTER TABLE items ALTER COLUMN ref_no TYPE text;
ALTER TABLE items ALTER COLUMN category TYPE text;
ALTER TABLE items ALTER COLUMN category_no TYPE text;
ALTER TABLE items ALTER COLUMN issuer TYPE text;
```

### 实体类

`Item.java` 中将四个字段从 `@Column(length = 500)` 改为 `@Column(columnDefinition = "TEXT")`：

```java
@Column(columnDefinition = "TEXT")
private String category;

@Column(name = "category_no", columnDefinition = "TEXT")
private String categoryNo;

@Column(name = "ref_no", columnDefinition = "TEXT")
private String refNo;

@Column(columnDefinition = "TEXT")
private String issuer;
```

---

## PostgreSQL TEXT vs varchar 说明

**TEXT 和 varchar（无长度限制）在 PostgreSQL 中底层实现完全相同**：

- 短值都内联存储在行中
- 超过 ~2KB 的值自动 TOAST（压缩 + 外存），对查询透明
- 索引行为完全一致（B-tree 索引前 N 字节，与类型无关）
- 不存在"TEXT 慢、varchar 快"的性能差异

因此将所有不确定长度的字段改为 TEXT 是合理的选择，不会带来任何性能或存储上的负面影响。

---

## 关键经验

1. **出现"value too long"时，先用 Python csv 诊断源文件** — 看列数异常行和列最大长度，两个指标能直接判断是解析问题还是字段长度问题。
2. **列数异常行 = 0 且某列真实超长 → 单纯长度不够**，直接改字段类型即可。
3. **列数异常行 > 0 → 先排查解析器**，盲目扩大字段会让错误数据入库。
4. **来自不同部门不同年代的 CSV 数据，格式差异大**，对标题、字号、分类这类无法预知上限的字段，直接用 TEXT 比反复踩坑更务实。
