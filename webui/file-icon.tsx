/**
 * 文件类型图标：@ 引用候选列表用。
 *
 * 与桌面端 FileMentionList 同一套图标（@react-symbols/icons）。桌面端用 FileIcon 的
 * 全量「文件名/扩展名 → 图标」映射，整包约 170KB（gzip）；WebUI 面向手机，这里改为
 * 显式命名导入 + 自己的常见扩展名表，Vite tree-shaking 后只保留实际用到的图标。
 *
 * 图标自带配色（固定色值，不随主题变化），所以不需要额外的深浅色样式。
 */
import * as React from 'react'
import {
  Audio, BracketsYellow, Bun, CLang, CodeBlue, CodeOrange, Compressed, Cplus, Csharp,
  Csv, Database, Docker, Document, Exe, Gear, Git, Go, Image, Java, Js, Kotlin, License,
  Lock, Markdown, NPM, Notebook, Patch, PDF, PHP, Python, Reactjs, Reactts, Ruby, Rust,
  Sass, Shell, SVG, Svelte, Swift, Text, Tsconfig, TypeScript, Video, Vite, Vue, XML,
  Yaml,
} from '@react-symbols/icons/files'
import { Folder, FolderOpen } from '@react-symbols/icons/folders'
import { fileBaseName } from './file-reference'

export type FileIconKind =
  | 'folder' | 'folder-open' | 'file'
  | 'pdf' | 'doc' | 'sheet' | 'markdown' | 'text' | 'json' | 'yaml' | 'xml' | 'config'
  | 'html' | 'css' | 'sass' | 'shell'
  | 'js' | 'jsx' | 'ts' | 'tsx' | 'vue' | 'svelte'
  | 'python' | 'go' | 'rust' | 'java' | 'kotlin' | 'swift' | 'c' | 'cpp' | 'csharp'
  | 'ruby' | 'php' | 'sql' | 'image' | 'svg' | 'video' | 'audio' | 'archive'
  | 'lock' | 'license' | 'notebook' | 'patch' | 'binary'
  | 'docker' | 'npm' | 'bun' | 'vite' | 'tsconfig' | 'git'

const EXTENSION_ICONS: Readonly<Record<string, FileIconKind>> = {
  pdf: 'pdf',
  doc: 'doc', docx: 'doc', rtf: 'doc', odt: 'doc',
  xls: 'sheet', xlsx: 'sheet', xlsm: 'sheet', xlsb: 'sheet', csv: 'sheet', tsv: 'sheet', ods: 'sheet',
  md: 'markdown', markdown: 'markdown', mdx: 'markdown',
  txt: 'text', log: 'text',
  json: 'json', jsonc: 'json', json5: 'json',
  yaml: 'yaml', yml: 'yaml',
  xml: 'xml',
  toml: 'config', ini: 'config', cfg: 'config', conf: 'config', properties: 'config',
  html: 'html', htm: 'html',
  css: 'css',
  scss: 'sass', sass: 'sass', less: 'sass',
  sh: 'shell', bash: 'shell', zsh: 'shell', fish: 'shell', ps1: 'shell', bat: 'shell', cmd: 'shell',
  js: 'js', mjs: 'js', cjs: 'js',
  jsx: 'jsx',
  ts: 'ts', mts: 'ts', cts: 'ts',
  tsx: 'tsx',
  vue: 'vue', svelte: 'svelte',
  py: 'python', pyw: 'python',
  go: 'go',
  rs: 'rust',
  java: 'java',
  kt: 'kotlin', kts: 'kotlin',
  swift: 'swift',
  c: 'c', h: 'c',
  cpp: 'cpp', cc: 'cpp', cxx: 'cpp', hpp: 'cpp', hh: 'cpp', hxx: 'cpp',
  cs: 'csharp',
  rb: 'ruby', erb: 'ruby',
  php: 'php',
  sql: 'sql', db: 'sql', sqlite: 'sql', sqlite3: 'sql',
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', webp: 'image', bmp: 'image',
  ico: 'image', tif: 'image', tiff: 'image', avif: 'image',
  svg: 'svg',
  mp4: 'video', webm: 'video', mov: 'video', mkv: 'video', avi: 'video', m4v: 'video',
  mp3: 'audio', wav: 'audio', flac: 'audio', m4a: 'audio', ogg: 'audio', aac: 'audio',
  zip: 'archive', tar: 'archive', gz: 'archive', tgz: 'archive', bz2: 'archive',
  xz: 'archive', rar: 'archive', '7z': 'archive',
  lock: 'lock',
  ipynb: 'notebook',
  patch: 'patch', diff: 'patch',
  exe: 'binary', dll: 'binary', msi: 'binary', dmg: 'binary', pkg: 'binary',
}

/** 无扩展名 / 以点开头的配置文件名：扩展名表匹配不到，按完整文件名归类 */
const FILE_NAME_ICONS: Readonly<Record<string, FileIconKind>> = {
  dockerfile: 'docker',
  makefile: 'config',
  license: 'license',
  licence: 'license',
  copying: 'license',
  'package.json': 'npm',
  'package-lock.json': 'npm',
  'npm-shrinkwrap.json': 'npm',
  'bun.lock': 'bun',
  'bun.lockb': 'bun',
  'bunfig.toml': 'bun',
  'tsconfig.json': 'tsconfig',
  '.gitignore': 'git',
  '.gitattributes': 'git',
  '.gitmodules': 'git',
  '.env': 'config',
  '.editorconfig': 'config',
}

const FILE_ICON_COMPONENTS: Readonly<Record<FileIconKind, React.ComponentType<React.SVGProps<SVGSVGElement>>>> = {
  folder: Folder,
  'folder-open': FolderOpen,
  file: Document,
  pdf: PDF,
  doc: Document,
  sheet: Csv,
  markdown: Markdown,
  text: Text,
  json: BracketsYellow,
  yaml: Yaml,
  xml: XML,
  config: Gear,
  html: CodeOrange,
  css: CodeBlue,
  sass: Sass,
  shell: Shell,
  js: Js,
  jsx: Reactjs,
  ts: TypeScript,
  tsx: Reactts,
  vue: Vue,
  svelte: Svelte,
  python: Python,
  go: Go,
  rust: Rust,
  java: Java,
  kotlin: Kotlin,
  swift: Swift,
  c: CLang,
  cpp: Cplus,
  csharp: Csharp,
  ruby: Ruby,
  php: PHP,
  sql: Database,
  image: Image,
  svg: SVG,
  video: Video,
  audio: Audio,
  archive: Compressed,
  lock: Lock,
  license: License,
  notebook: Notebook,
  patch: Patch,
  binary: Exe,
  docker: Docker,
  npm: NPM,
  bun: Bun,
  vite: Vite,
  tsconfig: Tsconfig,
  git: Git,
}

/** 扩展名 → 图标类型；未知类型落到通用文件图标 */
export function resolveFileIconKind(name: string, isDirectory: boolean, isOpen = false): FileIconKind {
  if (isDirectory) return isOpen ? 'folder-open' : 'folder'
  const base = fileBaseName(name).toLowerCase()
  const byName = FILE_NAME_ICONS[base]
  if (byName) return byName
  // `.env.local` 这类前缀是一个系列，不能只认 `.env` 本身
  if (base.startsWith('.env.')) return 'config'
  if (base.startsWith('docker-compose.')) return 'docker'
  if (base.startsWith('tsconfig.') && base.endsWith('.json')) return 'tsconfig'
  if (base.startsWith('vite.config.')) return 'vite'
  const dot = base.lastIndexOf('.')
  if (dot <= 0) return 'file'
  return EXTENSION_ICONS[base.slice(dot + 1)] ?? 'file'
}

export interface FileTypeIconProps {
  /** 文件名（也可以传完整路径，内部只取最后一段） */
  name: string
  isDirectory: boolean
  isOpen?: boolean
  size?: number
}

export const FileTypeIcon = React.memo(function FileTypeIcon({
  name, isDirectory, isOpen = false, size = 16,
}: FileTypeIconProps): React.ReactElement {
  const kind = resolveFileIconKind(name, isDirectory, isOpen)
  const Icon = FILE_ICON_COMPONENTS[kind]
  return (
    // 名称文字就在旁边，图标对读屏是装饰
    <span className="file-type-icon" style={{ width: size, height: size }} aria-hidden="true">
      <Icon width={size} height={size} />
    </span>
  )
})
