<template>
  <main class="page collection-page">
    <header class="simple-heading"><h1>收藏</h1><p>收着平时看到的好文章、好工具<span v-if="!error"> · 共 {{ items?.length || 0 }} 条<span v-if="groups.length">，{{ groups.length }} 个分组</span></span></p></header>
    <p v-if="pending" class="state">加载中…</p><div v-else-if="error" class="state">收藏暂时读不到，请稍后再试。<button class="quiet-button" @click="refresh()">重试</button></div>
    <p v-else-if="!items?.length" class="state">这里还空着 —— 还没有收藏任何东西。</p>
    <div v-else class="favorite-groups"><section v-for="group in groups" :key="group.name" class="favorite-group pixel-panel"><h2>{{ group.name }}<span>{{ group.items.length }} 条</span></h2><ul><li v-for="item in group.items" :key="item.id"><a v-if="externalUrl(item.primaryUrl)" :href="item.primaryUrl" target="_blank" rel="noopener noreferrer">{{ item.title }}</a><span v-else>{{ item.title }}（地址缺失，打不开）</span><p v-if="item.description">{{ item.description }}</p><small>{{ hostOf(item.primaryUrl) }}</small></li></ul></section></div>
  </main>
</template>
<script setup lang="ts">
import { externalUrl, hostOf, type SiteItem } from '~/utils/siteTypes'
const config = useRuntimeConfig()
const { data: items, pending, error, refresh } = await useFetch<SiteItem[]>(`${config.public.apiBase}/favorites`)
const groups = computed(() => {
  const grouped = new Map<string, SiteItem[]>()
  for (const item of items.value || []) { const key = item.secondaryUrl?.trim() || '未分组'; grouped.set(key, [...(grouped.get(key) || []), item]) }
  return [...grouped].map(([name, items]) => ({ name, items }))
})
useSeoMeta({ title: '收藏 · 亿轨星途' })
</script>
