<template>
  <div class="site-shell">
    <header class="site-header">
      <NuxtLink class="brand" to="/">亿轨星途</NuxtLink>
      <nav aria-label="主导航">
        <NuxtLink to="/">首页</NuxtLink>
        <NuxtLink to="/articles">文章</NuxtLink>
        <NuxtLink to="/projects">项目</NuxtLink>
        <NuxtLink to="/music">音乐</NuxtLink>
        <NuxtLink to="/links">友链</NuxtLink>
        <NuxtLink to="/favorites">收藏</NuxtLink>
        <NuxtLink to="/about">关于</NuxtLink>
      </nav>
      <NuxtLink class="admin-link" to="/login?redirect=%2Fadmin">站长入口</NuxtLink>
    </header>
    <NuxtPage />
    <footer class="site-footer">© {{ new Date().getFullYear() }} 亿轨星途 <NuxtLink to="/archive">归档</NuxtLink></footer>
  </div>
</template>
