<template>
  <main class="page">
    <header class="page-heading"><p class="eyebrow">ABOUT</p><h1>关于</h1></header>
    <div v-if="content" class="article-content" v-html="safeContent" />
    <p v-else class="state">关于内容正在整理中。</p>
  </main>
</template>

<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import sanitizeHtml from 'sanitize-html'
const config = useRuntimeConfig()
const { data } = await useFetch<{ content?: string }>(`${config.public.apiBase}/about`)
const content = computed(() => data.value?.content ?? '')
const safeContent = computed(() => sanitizeHtml(new MarkdownIt({ html: false, linkify: true, breaks: true }).render(content.value)))
</script>
