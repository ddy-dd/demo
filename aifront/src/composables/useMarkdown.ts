/**
 * useMarkdown — Markdown + KaTeX 数学公式渲染
 *
 * 从 SimpleChat.vue 提取的共享渲染函数，供对话和语音通话等组件使用。
 */
import { marked } from 'marked'
import katex from 'katex'
import 'katex/dist/katex.min.css'

/**
 * 将文本渲染为 HTML（Markdown + 数学公式）
 *
 * 处理顺序：
 * 1. 先用占位符替换所有数学公式（$$...$$ 和 $...$）
 * 2. 用 marked 解析 Markdown
 * 3. 还原 KaTeX 渲染后的公式 HTML
 *
 * 保证 marked 不会破坏公式中的特殊字符（如下划线、星号）
 */
export function renderMarkdown(text: string): string {
  if (!text) return ''

  // 1) 用占位符替换所有数学公式，防止被 marked 破坏
  const placeholders: string[] = []
  let idx = 0

  // 块级公式 $$...$$ 优先
  text = text.replace(/\$\$([\s\S]*?)\$\$/g, (_, math: string) => {
    const key = `\x00MATH${idx}\x00`
    placeholders[idx] = katex.renderToString(math.trim(), {
      displayMode: true,
      throwOnError: false,
    })
    idx++
    return key
  })

  // 行内公式 $...$
  text = text.replace(/\$([^\$]+)\$/g, (_, math: string) => {
    const key = `\x00MATH${idx}\x00`
    placeholders[idx] = katex.renderToString(math.trim(), {
      displayMode: false,
      throwOnError: false,
    })
    idx++
    return key
  })

  // 2) 渲染 markdown
  let html = marked.parse(text, { async: false }) as string

  // 3) 还原数学公式
  placeholders.forEach((katexHtml, i) => {
    html = html.replace(`\x00MATH${i}\x00`, katexHtml)
  })

  return html
}

/** 去掉 markdown 标记，只留纯文本（用于展示思考过程摘要等） */
export function stripMarkdown(text: string): string {
  if (!text) return text
  return text
    .replace(/```[\s\S]*?```/g, '')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]+\)/g, '$1')
    .replace(/\*\*(.+?)\*\*/g, '$1').replace(/__(.+?)__/g, '$1')
    .replace(/\*(.+?)\*/g, '$1').replace(/_(.+?)_/g, '$1')
    .replace(/~~(.+?)~~/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^>\s+/gm, '')
    .replace(/^[\s]*[-*+]\s+\[[ x]\]\s+/gm, '')
    .replace(/^[\s]*[-*+]\s+/gm, '')
    .replace(/^[\s]*\d+\.\s+/gm, '')
    .replace(/^-{3,}$|^\*{3,}$|^_{3,}$/gm, '\n\n')
    .replace(/\n{4,}/g, '\n\n\n')
    .trim()
}
