<template>
  <article class="article-card" :class="{ 'home-card': home, 'first-card': index === 0 }">
    <NuxtLink class="card-cover" :to="`/article/${article.id}`" :prefetch="false"><img :src="article.cover || `/cover-${index % 3 + 1}.png`" :alt="article.title" loading="lazy"><span v-if="!home" class="card-index">{{ String(index + 1).padStart(2, '0') }}</span></NuxtLink>
    <div class="card-body">
      <span v-if="home" class="card-category">{{ article.categoryName || '未分类' }}</span>
      <div v-else class="card-meta"><span>{{ article.categoryName || '未分类' }}</span><time>{{ articleDate(article.createTime) }}</time></div>
      <h3><NuxtLink :to="`/article/${article.id}`" :prefetch="false">{{ article.title }}</NuxtLink></h3><p v-if="article.summary" class="card-summary">{{ article.summary }}</p>
      <div v-if="home && article.tags?.length" class="card-tags"><button v-for="tag in article.tags" :key="tag.id" type="button" @click.prevent.stop="$emit('tag', String(tag.id))">#{{ tag.name }}</button></div>
      <div class="card-foot"><time v-if="home">{{ articleDate(article.createTime) }}</time><span>{{ article.viewCount || 0 }} 次浏览</span><NuxtLink class="read-link" :to="`/article/${article.id}`" :prefetch="false">{{ home ? '阅读全文 ↗' : '打开文章 →' }}</NuxtLink></div>
    </div>
  </article>
</template>
<script setup lang="ts">
import { articleDate, type ArticleSummary } from '~/utils/articleTypes'
defineProps<{ article: ArticleSummary; index: number; home?: boolean }>()
defineEmits<{ tag: [value: string] }>()
</script>
