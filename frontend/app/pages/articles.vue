<template>
  <main class="page articles-page">
    <header class="articles-hero pixel-panel"><div><p class="eyebrow">ARTICLE LIBRARY</p><h1>文章图鉴</h1><p>记录学习、生活与所思所想，按关键词、分类和标签寻找文章。</p></div><div class="hero-count"><strong>{{ total }}</strong><span>篇{{ isFiltered ? '符合筛选' : '已发布' }}</span></div></header>
    <ArticleFilters v-model="keyword" :category-id="categoryId" :tag-id="tagId" :categories="categories" :tags="tags" @search="search" @category="selectCategory" @tag="selectTag" />
    <header class="list-heading"><div><p class="eyebrow">QUEST LOG</p><h2>{{ title }}</h2></div><button v-if="isFiltered" type="button" class="quiet-button" @click="clear">清除筛选</button></header>
    <p v-if="pending && !articles.length" class="state">加载中…</p>
    <div v-else-if="error" class="state">文章暂时读不到。<button type="button" @click="refresh()">重试</button></div>
    <p v-else-if="!articles.length" class="state">{{ isFiltered ? '没有找到符合条件的文章。' : '还没有发布文章。' }}</p>
    <div v-else class="article-grid" :aria-busy="pending"><ArticleCard v-for="(article, i) in articles" :key="article.id" :article="article" :index="(page - 1) * size + i" /></div>
    <ArticlePagination v-if="total" :total="total" :size="size" :page="page" :count="pageCount" @page="changePage" @size="changeSize" />
  </main>
</template>
<script setup lang="ts">
const browser = useArticleBrowser()
await browser.ready
const { keyword, categoryId, tagId, page, size, articles, total, pending, error, refresh, categories, tags,
  pageCount, isFiltered, title, search, selectCategory, selectTag, changePage, changeSize, clear } = browser
useSeoMeta({ title: '文章 · 亿轨星途', description: '记录学习、生活与所思所想，按关键词、分类和标签寻找文章。' })
</script>
