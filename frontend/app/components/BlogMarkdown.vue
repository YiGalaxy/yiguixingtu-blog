<template>
  <MdPreview :id="id" :model-value="content" :theme="theme === 'day' ? 'light' : 'dark'" language="zh-CN"
    :md-heading-id="headingId" :sanitize="sanitizePreview" @on-get-catalog="updateCatalog" />
</template>
<script setup lang="ts">
import { MdPreview, type HeadList } from 'md-editor-v3'
import 'md-editor-v3/lib/preview.css'
import { sanitizePreview, type ArticleHeading } from '~/utils/markdown'
defineProps<{ content: string; id: string }>()
const emit = defineEmits<{ catalog: [headings: ArticleHeading[]] }>()
const theme = useState<'night' | 'day'>('site-theme', () => 'night')
const headingId = ({ index }: { index: number }) => `heading-${index + 1}`
const updateCatalog = (list: HeadList[]) => emit('catalog', list.map((heading, index) => ({
  id: headingId({ index }), text: heading.text, level: heading.level,
})))
</script>
<style scoped>
/* Keep these variables more specific than the renderer's lazily loaded theme. */
.md-editor {
  --md-bk-color: transparent;
  --md-color: var(--ink);
  --md-border-color: var(--line);
  --md-hover-color: var(--accent);
  --md-scrollbar-bg-color: transparent;
  --md-scrollbar-thumb-color: var(--line);
}
</style>
