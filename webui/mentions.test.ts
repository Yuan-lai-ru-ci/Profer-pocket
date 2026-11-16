/**
 * 提及的行为测试：语义与桌面端一致 —— `/` 触发 Skill、`@` 引用文件。
 *
 * token 形态与桌面端一致（/skill:<slug>、@file:<path>），提取错了技能路由就加载不到；
 * 触发符上下文判错则邮箱/URL/路径会误弹建议。
 */
import { describe, expect, test } from 'bun:test'
import {
  buildFileGroups, buildFileTree, decodeFileTokenPath, encodeFileTokenPath,
  extractMentionedSkills, filterFiles, filterSkills, flattenFileTree,
  isCommittedMentionQuery,
  resolveFileQuery, resolveMentionQuery, resolveSkillQuery,
} from './mentions'

describe('提及提取', () => {
  test('Given 正文含 /skill token When 提取 Then 得出去重的 slug 列表', () => {
    expect(extractMentionedSkills('用 /skill:review 帮我看 /skill:deploy')).toEqual(['review', 'deploy'])
  })

  test('Given 重复提及 When 提取 Then 去重', () => {
    expect(extractMentionedSkills('/skill:a 然后 /skill:a')).toEqual(['a'])
  })

  test('Given 没有 token When 提取 Then 空数组', () => {
    expect(extractMentionedSkills('普通消息')).toEqual([])
  })
})

describe('提及上下文判定', () => {
  test('Given 行首或空白后输入 @ When 解析 Then 返回查询串与起点', () => {
    expect(resolveMentionQuery('@', 1)).toEqual({ query: '', start: 0 })
    expect(resolveMentionQuery('用 @rev', 6)).toEqual({ query: 'rev', start: 2 })
    expect(resolveMentionQuery('第一行\n@de', 7)).toEqual({ query: 'de', start: 4 })
  })

  test('Given @ 前面是普通字符（邮箱/路径） When 解析 Then 不触发', () => {
    expect(resolveMentionQuery('mail@ex', 7)).toBeNull()
    expect(resolveMentionQuery('/path@x', 7)).toBeNull()
  })

  test('Given @ 与光标之间有空白 When 解析 Then 不触发（早已结束）', () => {
    expect(resolveMentionQuery('@ done', 6)).toBeNull()
  })

  test('Given @ 之后出现第二个 @ When 解析 Then 以最后一个为准', () => {
    expect(resolveMentionQuery('@a @b', 5)).toEqual({ query: 'b', start: 3 })
  })
})

describe('已确认 token 的上下文（点回输入框不重弹）', () => {
  test('Given 光标落在 @file: / /skill: token 内 When 判定 Then 视为正文而非查询', () => {
    expect(isCommittedMentionQuery('file:a.ts')).toBe(true)
    expect(isCommittedMentionQuery('skill:review')).toBe(true)
    expect(isCommittedMentionQuery('file:')).toBe(true)
  })

  test('Given 普通查询串 When 判定 Then 仍按查询处理', () => {
    expect(isCommittedMentionQuery('')).toBe(false)
    expect(isCommittedMentionQuery('report')).toBe(false)
    expect(isCommittedMentionQuery('files')).toBe(false)
  })

  test('Given 光标在完整 token 内 When 解析后判定 Then 不弹候选', () => {
    const fileToken = resolveFileQuery('看 @file:a.ts 这份', 12)!
    expect(isCommittedMentionQuery(fileToken.query)).toBe(true)
    const skillToken = resolveSkillQuery('/skill:review', 13)!
    expect(isCommittedMentionQuery(skillToken.query)).toBe(true)
    const typing = resolveFileQuery('看 @a.t', 6)!
    expect(isCommittedMentionQuery(typing.query)).toBe(false)
  })
})

describe('候选过滤', () => {
  const skills = [
    { slug: 'review', name: '代码评审', description: '' },
    { slug: 'deploy', name: '部署助手', description: '' },
  ]

  test('Given 空查询 When 过滤 Then 全量（不再硬截断到 8）', () => {
    expect(filterSkills(skills, '')).toHaveLength(2)
    const many = Array.from({ length: 20 }, (_, index) => ({ slug: `s${index}`, name: `技能${index}`, description: '' }))
    expect(filterSkills(many, '')).toHaveLength(20)
  })

  test('Given 查询命中 slug 或名称 When 过滤 Then 不分大小写', () => {
    expect(filterSkills(skills, 'REV')).toEqual([skills[0]])
    expect(filterSkills(skills, '部署')).toEqual([skills[1]])
  })

  test('Given 无命中 When 过滤 Then 空（面板显示无结果）', () => {
    expect(filterSkills(skills, 'zzz')).toEqual([])
  })
})

describe('`/` 技能触发（回归：曾错误地用 @ 触发技能）', () => {
  test('Given 行首或空白后输入 / When 解析 Then 返回查询串与起点', () => {
    expect(resolveSkillQuery('/', 1)).toEqual({ query: '', start: 0 })
    expect(resolveSkillQuery('用 /rev', 7)).toEqual({ query: 'rev', start: 2 })
    expect(resolveSkillQuery('第一行\n/skill:de', 13)).toEqual({ query: 'skill:de', start: 4 })
  })

  test('Given URL 或路径里的 / When 解析 Then 不触发', () => {
    expect(resolveSkillQuery('https://x', 9)).toBeNull()
    expect(resolveSkillQuery('用 a/b', 6)).toBeNull()
  })

  test('Given / 与光标之间有空白 When 解析 Then 不触发', () => {
    expect(resolveSkillQuery('/ done', 6)).toBeNull()
  })

  test('Given / 与 @ 同时出现 When 分别解析 Then 各自命中自己的触发符', () => {
    expect(resolveSkillQuery('@file:a.ts /rev', 15)).toEqual({ query: 'rev', start: 11 })
    expect(resolveFileQuery('@file:a.ts /rev', 15)).toBeNull()
    expect(resolveFileQuery('/rev @file:a.ts', 15)).toEqual({ query: 'file:a.ts', start: 5 })
    expect(resolveSkillQuery('/rev @file:a.ts', 15)).toBeNull()
  })
})

describe('文件候选过滤', () => {
  const files = [
    { path: 'src/main.ts', name: 'main.ts', type: 'file' as const },
    { path: 'docs/readme.md', name: 'readme.md', type: 'file' as const },
  ]

  test('Given 查询命中文件名或相对路径 When 过滤 Then 都返回', () => {
    expect(filterFiles(files, 'MAIN')).toEqual([files[0]])
    expect(filterFiles(files, 'docs/')).toEqual([files[1]])
  })

  test('Given 空查询 When 过滤 Then 返回全部候选', () => {
    expect(filterFiles(files, '')).toHaveLength(2)
  })
})

describe('文件 token 路径编码（带空格文件名）', () => {
  test('Given 空格/中文路径 When 编码 Then 无空白且可还原', () => {
    const path = '2020_tai_pre (1).pdf'
    const encoded = encodeFileTokenPath(path)
    expect(encoded).not.toContain(' ')
    expect(/^\S+$/.test(encoded)).toBe(true)
    expect(decodeFileTokenPath(encoded)).toBe(path)
  })

  test('Given 旧 token 原样路径 When 解码 Then 原样返回', () => {
    expect(decodeFileTokenPath('发票.pdf')).toBe('发票.pdf')
  })
})

describe('文件候选分组（会话文件 / 工作区文件）', () => {
  test('Given 两组候选 When 分组建树 Then 工作区组剥掉前缀但保留完整引用路径', () => {
    const groups = buildFileGroups([
      { path: '附件.pdf', name: '附件.pdf', type: 'file', source: 'session' },
      { path: '../workspace-files/shared.md', name: 'shared.md', type: 'file', source: 'workspace' },
      { path: '../workspace-files/assets/logo.png', name: 'logo.png', type: 'file', source: 'workspace' },
    ])
    expect(groups.session.map((node) => node.name)).toEqual(['附件.pdf'])
    expect(groups.workspace.map((node) => node.name)).toEqual(['assets', 'shared.md'])
    const shared = groups.workspace.find((node) => node.name === 'shared.md')!
    // 建树路径干净，引用路径带 ../workspace-files/ 前缀
    expect(shared.path).toBe('shared.md')
    expect(shared.refPath).toBe('../workspace-files/shared.md')
    const logo = groups.workspace[0]!.children[0]!
    expect(logo.refPath).toBe('../workspace-files/assets/logo.png')
  })
})

describe('文件候选树（@ 要能看见目录）', () => {
  test('Given 扁平 path 列表 When 建树 Then 目录在前、文件在后，层级完整', () => {
    const tree = buildFileTree([
      { path: 'src/deep/util.ts', name: 'util.ts', type: 'file' },
      { path: 'src/main.ts', name: 'main.ts', type: 'file' },
      { path: 'README.md', name: 'README.md', type: 'file' },
      { path: 'src', name: 'src', type: 'dir' },
    ])
    expect(tree.map((node) => node.name)).toEqual(['src', 'README.md'])
    const src = tree[0]!
    expect(src.type).toBe('dir')
    expect(src.children.map((node) => node.name)).toEqual(['deep', 'main.ts'])
    const deep = src.children[0]!
    expect(deep.type).toBe('dir')
    expect(deep.children[0]?.path).toBe('src/deep/util.ts')
    expect(deep.children[0]?.depth).toBe(2)
  })

  test('Given 缺中间目录的候选 When 建树 Then 补虚拟祖先，层级不断', () => {
    const tree = buildFileTree([
      { path: 'a/b/c.ts', name: 'c.ts', type: 'file' },
    ])
    expect(tree[0]?.name).toBe('a')
    expect(tree[0]?.children[0]?.name).toBe('b')
    expect(tree[0]?.children[0]?.children[0]?.path).toBe('a/b/c.ts')
  })

  test('Given 展开集合 When 展平 Then 只带出已展开目录的子项', () => {
    const tree = buildFileTree([
      { path: 'src/a.ts', name: 'a.ts', type: 'file' },
      { path: 'src/b.ts', name: 'b.ts', type: 'file' },
    ])
    expect(flattenFileTree(tree, new Set()).map((node) => node.path)).toEqual(['src'])
    const flat = flattenFileTree(tree, new Set(['src'])).map((node) => node.path)
    expect(flat).toEqual(['src', 'src/a.ts', 'src/b.ts'])
  })
})
