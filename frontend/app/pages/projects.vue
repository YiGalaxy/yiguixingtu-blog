<template>
  <main class="page">
    <header class="page-heading"><p class="eyebrow">SELECTED WORK</p><h1>项目</h1><p>记录正在构建的作品和实践。</p></header>
    <p v-if="pending" class="state">正在读取项目…</p>
    <p v-else-if="!items?.length" class="state">还没有添加项目。</p>
    <div v-else class="collection-list"><article v-for="item in items" :key="item.id" class="collection-row"><div><h2>{{ item.title }}</h2><p>{{ item.description }}</p><small>{{ item.metadata }}</small></div><div class="collection-actions"><a v-if="item.primaryUrl" :href="item.primaryUrl" target="_blank" rel="noopener noreferrer">在线演示 ↗</a><a v-if="item.secondaryUrl" :href="item.secondaryUrl" target="_blank" rel="noopener noreferrer">源代码 ↗</a></div></article></div>
  </main>
</template>

<script setup lang="ts">
const config = useRuntimeConfig()
const { data: items, pending } = await useFetch<Array<{ id: number; title: string; description?: string; primaryUrl?: string; secondaryUrl?: string; metadata?: string }>>(`${config.public.apiBase}/projects`)
</script>
