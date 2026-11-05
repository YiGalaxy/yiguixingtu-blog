<template>
  <main class="page">
    <header class="page-heading"><p class="eyebrow">ARCHIVE</p><h1>归档</h1><p>按发布时间回看文章。</p></header>
    <p v-if="pending" class="state">正在读取归档…</p>
    <p v-else-if="!months?.length" class="state">还没有已发布文章。</p>
    <div v-else class="archive-list"><section v-for="month in months" :key="`${month.year}-${month.month}`" class="archive-row"><strong>{{ month.year }}年{{ String(month.month).padStart(2, '0') }}月</strong><NuxtLink :to="{ path: '/articles', query: { year: String(month.year), month: String(month.month) } }">{{ month.count }} 篇文章</NuxtLink></section></div>
  </main>
</template>

<script setup lang="ts">
const config = useRuntimeConfig()
const { data: months, pending } = await useFetch<Array<{ year: number; month: number; count: number }>>(`${config.public.apiBase}/archive`)
</script>
