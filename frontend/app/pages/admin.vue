<template>
  <main class="page admin-page">
    <header class="page-heading"><p class="eyebrow">SITE MANAGEMENT</p><h1>站点管理</h1></header>
    <nav class="admin-tabs" aria-label="管理分组">
      <button v-for="group in groups" :key="group.id" type="button" :aria-pressed="activeGroup === group.id" @click="activeGroup = group.id">{{ group.label }}</button>
    </nav>
    <section class="admin-content">
      <h2>{{ active.label }}</h2>
      <ul><li v-for="item in active.items" :key="item">{{ item }}</li></ul>
      <p v-if="activeGroup === 'content'" class="state">登录后可管理文章与待审核评论。</p>
      <p v-if="activeGroup === 'site'" class="state">站点公告在设置中统一编辑，留空时首页不显示。</p>
    </section>
  </main>
</template>

<script setup lang="ts">
const groups = [
  { id: 'content', label: '内容', items: ['文章', '分类', '标签', '评论'] },
  { id: 'display', label: '展示', items: ['项目', '收藏', '友链', '音乐'] },
  { id: 'site', label: '站点', items: ['关于', '站点设置', '存储'] },
  { id: 'overview', label: '概览', items: ['访问统计', '用户管理'] },
]
const activeGroup = ref('content')
const active = computed(() => groups.find(group => group.id === activeGroup.value) ?? groups[0]!)
</script>
