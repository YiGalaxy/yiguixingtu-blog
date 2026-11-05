<template>
  <div class="site-shell">
    <img class="bg-poster" :src="backgroundImage" alt="" aria-hidden="true">
    <header class="site-header">
      <NuxtLink class="brand" to="/">
        <span class="brand-mark"><img src="/assets/brand-minecraft-pixel-transparent.png" alt=""></span>
        <span class="brand-name">亿轨星途</span>
      </NuxtLink>
      <nav class="nav-center" aria-label="主导航">
        <NuxtLink class="nv" to="/"><span class="pixel-ui-icon icon-home" aria-hidden="true" />首页</NuxtLink>
        <NuxtLink class="nv" to="/articles"><span class="pixel-ui-icon icon-article" aria-hidden="true" />文章</NuxtLink>
        <NuxtLink class="nv" to="/projects"><span class="pixel-ui-icon icon-project" aria-hidden="true" />项目</NuxtLink>
        <NuxtLink class="nv" to="/music"><span class="pixel-ui-icon icon-music" aria-hidden="true" />音乐</NuxtLink>
        <NuxtLink class="nv" to="/links"><span class="pixel-ui-icon icon-links" aria-hidden="true" />友链</NuxtLink>
        <NuxtLink class="nv" to="/favorites"><span class="pixel-ui-icon icon-favorite" aria-hidden="true" />收藏</NuxtLink>
        <NuxtLink class="nv" to="/about"><span class="pixel-ui-icon icon-about" aria-hidden="true" />关于</NuxtLink>
      </nav>
      <div class="nav-right">
        <button class="theme-toggle" type="button" :aria-label="themeMode === 'night' ? '切换到白天主题' : '切换到夜晚主题'" @click="toggleTheme">{{ themeMode === 'night' ? '☼' : '☾' }}</button>
        <NuxtLink class="admin-link" to="/login?redirect=%2Fadmin"><span class="pixel-ui-icon icon-workbench" aria-hidden="true" />后台</NuxtLink>
      </div>
    </header>
    <NuxtPage />
    <footer class="site-footer">© {{ new Date().getFullYear() }} 亿轨星途 <NuxtLink to="/archive">归档</NuxtLink></footer>
  </div>
</template>

<script setup lang="ts">
const themeMode = ref<'night' | 'day'>('night')
const backgroundImage = computed(() => themeMode.value === 'night' ? '/assets/homepage-night-clear.png' : '/assets/homepage-day-bg.png')
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
