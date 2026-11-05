<template>
  <main class="page collection-page">
    <header class="simple-heading"><h1>友链</h1><p>这些站点，是我也常去看的地方<span v-if="!error"> · 共 {{ items?.length || 0 }} 个</span></p></header>
    <p v-if="pending" class="state">加载中…</p><div v-else-if="error" class="state">友链暂时读不到，请稍后再试。<button class="quiet-button" @click="refresh()">重试</button></div>
    <p v-else-if="!items?.length" class="state">这里还空着 —— 还没有添加任何友链。</p>
    <ul v-else class="friend-grid"><li v-for="item in items" :key="item.id" class="friend-card pixel-panel"><img v-if="item.secondaryUrl" :src="item.secondaryUrl" :alt="item.title" loading="lazy"><span v-else class="friend-initial">{{ [...item.title][0] }}</span><div><a v-if="externalUrl(item.primaryUrl)" :href="item.primaryUrl" target="_blank" rel="noopener noreferrer">{{ item.title }}</a><strong v-else>{{ item.title }}（地址缺失）</strong><p v-if="item.description">{{ item.description }}</p><small>{{ hostOf(item.primaryUrl) }}</small></div></li></ul>
  </main>
</template>
<script setup lang="ts">
import { externalUrl, hostOf, type SiteItem } from '~/utils/siteTypes'
const config = useRuntimeConfig()
const { data: items, pending, error, refresh } = await useFetch<SiteItem[]>(`${config.public.apiBase}/links`)
useSeoMeta({ title: '友链 · 亿轨星途' })
</script>
