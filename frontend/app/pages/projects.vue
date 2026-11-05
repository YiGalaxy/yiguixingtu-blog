<template>
  <main class="page collection-page">
    <header class="simple-heading"><h1>项目</h1><p>做过的东西，都摆在这里<span v-if="!error"> · 共 {{ items?.length || 0 }} 个</span></p></header>
    <p v-if="pending" class="state">加载中…</p>
    <div v-else-if="error" class="state">项目暂时读不到，请稍后再试。<button class="quiet-button" @click="refresh()">重试</button></div>
    <p v-else-if="!items?.length" class="state">这里还空着 —— 还没有放上任何项目。</p>
    <ul v-else class="project-grid"><li v-for="item in items" :key="item.id" class="project-card pixel-panel">
      <div class="project-cover"><img v-if="item.cover" :src="item.cover" :alt="item.title" loading="lazy"><span v-else>{{ [...item.title][0] }}</span></div>
      <div class="project-body"><h2>{{ item.title }}</h2><p v-if="item.description">{{ item.description }}</p><ul v-if="item.metadata" class="project-tech"><li v-for="tech in item.metadata.split(/[,，]/).map(t => t.trim()).filter(Boolean)" :key="tech">{{ tech }}</li></ul>
        <div class="project-actions"><a v-if="externalUrl(item.primaryUrl)" :href="item.primaryUrl" target="_blank" rel="noopener noreferrer">在线演示</a><a v-if="externalUrl(item.secondaryUrl)" :href="item.secondaryUrl" target="_blank" rel="noopener noreferrer" class="repo-link">代码仓库</a></div>
        <p v-if="!externalUrl(item.primaryUrl) && !externalUrl(item.secondaryUrl)" class="muted">这个项目还没有填在线地址或仓库地址，暂时点不开。</p>
      </div>
    </li></ul>
  </main>
</template>
<script setup lang="ts">
import { externalUrl, type SiteItem } from '~/utils/siteTypes'
const config = useRuntimeConfig()
const { data: items, pending, error, refresh } = await useFetch<SiteItem[]>(`${config.public.apiBase}/projects`)
useSeoMeta({ title: '项目 · 亿轨星途' })
</script>
