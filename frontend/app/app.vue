<template>
  <div class="site-shell">
    <img class="bg-poster" :src="backgroundImage" alt="" aria-hidden="true">
    <header class="site-header">
      <NuxtLink class="brand" to="/">
        <span class="brand-mark"><img src="/assets/brand-minecraft-pixel-transparent.png" alt=""></span>
        <span class="brand-name">{{ settings?.siteName || '亿轨星途' }}</span>
      </NuxtLink>
      <button class="menu-toggle" type="button" :aria-expanded="navOpen" aria-controls="main-nav" aria-label="展开导航" @click="navOpen = !navOpen">☰</button>
      <nav id="main-nav" class="nav-center" :class="{ open: navOpen }" aria-label="主导航">
        <NuxtLink class="nv" to="/"><span class="pixel-ui-icon icon-home" aria-hidden="true" />首页</NuxtLink>
        <NuxtLink class="nv" to="/articles"><span class="pixel-ui-icon icon-article" aria-hidden="true" />文章</NuxtLink>
        <NuxtLink class="nv" to="/favorites"><span class="pixel-ui-icon icon-favorite" aria-hidden="true" />收藏</NuxtLink>
        <NuxtLink class="nv" to="/projects"><span class="pixel-ui-icon icon-project" aria-hidden="true" />项目</NuxtLink>
        <NuxtLink class="nv" to="/links"><span class="pixel-ui-icon icon-links" aria-hidden="true" />友链</NuxtLink>
        <NuxtLink class="nv" to="/about"><span class="pixel-ui-icon icon-about" aria-hidden="true" />关于</NuxtLink>
      </nav>
      <div class="nav-right">
        <button class="theme-toggle" type="button" :aria-label="themeMode === 'night' ? '切换到白天主题' : '切换到夜晚主题'" @click="toggleTheme"><span class="theme-glyph" :class="themeMode" aria-hidden="true" /></button>
        <NuxtLink class="admin-link" to="/login?redirect=%2Fadmin"><span class="pixel-ui-icon icon-workbench" aria-hidden="true" />后台</NuxtLink>
      </div>
    </header>
    <NuxtPage />
    <footer class="site-footer"><div class="footer-brand"><img src="/assets/brand-minecraft-pixel-transparent.png" alt="">{{ settings?.siteName || '亿轨星途' }}</div>
      <div class="footer-meta"><span>{{ settings?.copyright || `© ${new Date().getFullYear()} 亿轨星途` }}</span><a v-if="settings?.icpNumber" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">{{ settings.icpNumber }}</a><a class="police-link" v-if="settings?.policeNumber" :href="policeUrl" target="_blank" rel="noopener noreferrer"><img class="police-icon" :src="policeIcon" alt="公安网安备案">{{ settings.policeNumber }}</a></div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import type { SiteSettings } from '~/utils/siteTypes'
const config = useRuntimeConfig()
const route = useRoute()
const navOpen = ref(false)
watch(() => route.fullPath, () => { navOpen.value = false })
const { data: settings } = await useFetch<SiteSettings>(`${config.public.apiBase}/site-settings`)
const policeIcon = '/assets/beian.png'
const policeUrl = computed(() => `https://beian.mps.gov.cn/#/query/webSearch?code=${settings.value?.policeNumber?.replace(/\D/g, '') || ''}`)
const themeMode = useState<'night' | 'day'>('site-theme', () => 'night')
const backgroundImage = computed(() => {
  const path = route.path
  const scene = path === '/projects' ? 'enchant-room' : path === '/favorites' ? 'brewing-room'
    : path === '/links' ? 'village-gathering' : path === '/about' ? 'fishing-river'
    : path === '/articles' || path.startsWith('/article/') ? 'article-library'
    : path === '/admin' || path === '/login' ? 'admin-studio' : ''
  return scene ? `/assets/${scene}-${themeMode.value}.png` : themeMode.value === 'night' ? '/assets/homepage-night-clear.png' : '/assets/homepage-day-bg.png'
})
const toggleTheme = () => {
  themeMode.value = themeMode.value === 'night' ? 'day' : 'night'
  if (import.meta.client) localStorage.setItem('blog-theme', themeMode.value)
}
onMounted(() => {
  if (localStorage.getItem('blog-theme') === 'day') themeMode.value = 'day'
})
watch(themeMode, value => {
  if (import.meta.client) document.documentElement.dataset.theme = value
}, { immediate: true })
</script>
