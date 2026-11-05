<template>
  <main class="page">
    <header class="page-heading"><p class="eyebrow">FRIENDS</p><h1>友链</h1><p>其他作者和站点。</p></header>
    <p v-if="pending" class="state">正在读取友链…</p>
    <p v-else-if="!items?.length" class="state">还没有添加友链。</p>
    <div v-else class="collection-list"><a v-for="item in items" :key="item.id" class="collection-row" :href="item.primaryUrl" target="_blank" rel="noopener noreferrer"><div><h2>{{ item.title }} ↗</h2><p>{{ item.description }}</p></div><img v-if="item.secondaryUrl" :src="item.secondaryUrl" :alt="item.title" class="friend-avatar"></a></div>
  </main>
</template>

<script setup lang="ts">
const config = useRuntimeConfig()
const { data: items, pending } = await useFetch<Array<{ id: number; title: string; description?: string; primaryUrl?: string; secondaryUrl?: string }>>(`${config.public.apiBase}/links`)
</script>
