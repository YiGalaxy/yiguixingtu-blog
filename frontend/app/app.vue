<template>
  <div class="site-shell">
    <header class="site-header">
      <NuxtLink class="brand" to="/">亿轨星途</NuxtLink>
      <nav aria-label="主导航">
        <NuxtLink to="/">首页</NuxtLink>
        <NuxtLink to="/articles">文章</NuxtLink>
        <NuxtLink to="/projects">项目</NuxtLink>
        <NuxtLink to="/about">关于</NuxtLink>
      </nav>
      <NuxtLink class="admin-link" to="/admin">管理后台</NuxtLink>
    </header>
    <NuxtPage />
    <footer class="site-footer">© {{ new Date().getFullYear() }} 亿轨星途</footer>
  </div>
</template>
