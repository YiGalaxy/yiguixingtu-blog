<template>
  <main class="page about-page">
    <header class="simple-heading"><h1>关于</h1><p>这个站是谁在写，以及怎么找到我</p></header>
    <p v-if="pending" class="state">加载中…</p>
    <div v-else-if="error" class="state">关于页暂时读不到，请稍后再试。<button class="quiet-button" @click="refresh()">重试</button></div>
    <section v-else class="about-card pixel-panel">
      <div class="about-identity"><img v-if="data?.avatar" :src="data.avatar" :alt="data.nickname || '站长'" class="about-avatar"><h2>{{ data?.nickname || '站长' }}</h2></div>
      <ul v-if="contacts.length" class="about-contacts"><li v-for="contact in contacts" :key="contact.label"><span>{{ contact.label }}</span><a v-if="contact.href" :href="contact.href" :target="contact.external ? '_blank' : undefined" rel="noopener noreferrer">{{ contact.value }}</a><span v-else>{{ contact.value }}</span></li></ul>
      <p v-else class="muted">站长还没有公开任何联系方式。</p>
      <section class="about-bio"><h3>自我介绍</h3><BlogMarkdown v-if="data?.content" id="about-preview" :content="data.content" /><p v-else class="muted">站长还没有写自我介绍。</p></section>
      <p v-if="data?.updatedAt" class="about-updated">最后更新：{{ data.updatedAt.replace('T', ' ').slice(0, 19) }}</p>
    </section>
  </main>
</template>
<script setup lang="ts">
import { externalUrl, type AboutProfile } from '~/utils/siteTypes'
const config = useRuntimeConfig()
const { data, pending, error, refresh } = await useFetch<AboutProfile>(`${config.public.apiBase}/about`)
const contacts = computed(() => [
  { label: '邮箱', value: data.value?.email, href: data.value?.email ? `mailto:${data.value.email}` : undefined, external: false },
  { label: 'GitHub', value: data.value?.github, href: externalUrl(data.value?.github) ? data.value?.github : undefined, external: true },
  { label: '微信', value: data.value?.wechat, href: undefined, external: false },
  { label: 'QQ', value: data.value?.qq, href: undefined, external: false },
].filter(c => c.value))
useSeoMeta({ title: '关于 · 亿轨星途' })
</script>
