<template>
  <main class="page">
    <header class="page-heading"><p class="eyebrow">BOOKMARKS</p><h1>收藏</h1><p>工具、文章与值得保留的链接。</p></header>
    <p v-if="pending" class="state">正在读取收藏…</p>
    <p v-else-if="!items?.length" class="state">还没有添加收藏。</p>
    <div v-else class="collection-list"><a v-for="item in items" :key="item.id" class="collection-row" :href="item.primaryUrl" target="_blank" rel="noopener noreferrer"><div><h2>{{ item.title }} ↗</h2><p>{{ item.description }}</p></div><small>{{ item.metadata }}</small></a></div>
  </main>
</template>

<script setup lang="ts">
const config = useRuntimeConfig()
const { data: items, pending } = await useFetch<Array<{ id: number; title: string; description?: string; primaryUrl?: string; metadata?: string }>>(`${config.public.apiBase}/favorites`)
</script>
