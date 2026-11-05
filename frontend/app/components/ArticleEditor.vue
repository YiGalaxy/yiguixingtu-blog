<template>
  <MdEditor v-model="content" :theme="theme === 'day' ? 'light' : 'dark'" language="zh-CN"
    :toolbars-exclude="['github', 'fullscreen', 'preview-html']" :sanitize="sanitizePreview" @on-upload-img="uploadImages" />
</template>
<script setup lang="ts">
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import { sanitizePreview } from '~/utils/markdown'

const content = defineModel<string>({ required: true })
const emit = defineEmits<{ uploading: [busy: boolean]; error: [message: string] }>()
const theme = useState<'night' | 'day'>('site-theme', () => 'night')
const config = useRuntimeConfig()
const token = useCookie<string | null>('blog-admin-token')
const uploadImages = async (files: File[], insert: (urls: string[]) => void) => {
  emit('uploading', true)
  const urls: string[] = []
  try {
    for (const file of files) {
      if (!['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(file.type) || file.size > 50 * 1024 * 1024) {
        throw new Error('请选择 50 MB 以内的 JPG、PNG、GIF 或 WebP 图片。')
      }
      const body = new FormData(); body.append('file', file)
      const result = await $fetch<{ url: string }>('/admin/uploads', {
        baseURL: config.public.apiBase, method: 'POST', body, headers: { Authorization: `Bearer ${token.value}` },
      })
      urls.push(result.url)
    }
  } catch (error) {
    emit('error', error instanceof Error ? error.message : '图片上传失败，请重试。')
  } finally {
    if (urls.length) insert(urls)
    emit('uploading', false)
  }
}
</script>
<style scoped>
.md-editor { height: 560px; --md-bk-color: var(--panel); --md-color: var(--ink); --md-border-color: var(--line); --md-toolbar-bg-color: var(--surface-2); }
@media (max-width: 560px) { .md-editor { height: 460px; } }
</style>
