<template>
  <section class="article-filters" :class="{ 'home-filters': home }" aria-label="查找文章">
    <form class="pixel-search" role="search" @submit.prevent="$emit('search')">
      <span aria-hidden="true">⌕</span><label class="sr-only" :for="home ? 'home-search' : 'article-search'">搜索文章</label>
      <input :id="home ? 'home-search' : 'article-search'" :value="modelValue" type="search" :placeholder="home ? '输入关键词搜索标题 / 摘要' : '搜索标题或摘要'" @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)">
      <button v-if="modelValue" type="button" aria-label="清除关键词" @click="$emit('update:modelValue', ''); $emit('search')">×</button>
    </form>
    <div class="filter-chips categories"><span v-if="!home" class="filter-label">分类</span>
      <button type="button" :class="{ selected: !categoryId }" @click="$emit('category', '')">全部</button>
      <button v-for="item in categories" :key="item.id" type="button" :class="{ selected: categoryId === String(item.id) }" @click="$emit('category', String(item.id))">{{ item.name }}</button>
    </div>
    <div v-if="tags?.length" class="filter-chips tags"><span v-if="!home" class="filter-label">标签</span>
      <button v-for="item in tags" :key="item.id" type="button" :aria-pressed="tagId === String(item.id)" :class="{ selected: tagId === String(item.id) }" @click="$emit('tag', String(item.id))">#{{ item.name }}</button>
    </div>
  </section>
</template>
<script setup lang="ts">
import type { ArticleTag } from '~/utils/articleTypes'
defineProps<{ modelValue: string; categoryId: string; tagId: string; categories?: ArticleTag[] | null; tags?: ArticleTag[] | null; home?: boolean }>()
defineEmits<{ 'update:modelValue': [value: string]; search: []; category: [value: string]; tag: [value: string] }>()
</script>
