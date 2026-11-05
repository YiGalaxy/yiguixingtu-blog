<template>
  <main class="page articles-page">
    <header class="page-heading">
      <p class="eyebrow">ARTICLE LIBRARY</p>
      <h1>文章</h1>
      <p>按关键词、分类和标签查找已发布的内容。</p>
    </header>
    <form class="search" role="search" @submit.prevent="applySearch">
      <label class="sr-only" for="article-search">搜索文章</label>
      <input id="article-search" v-model="keyword" type="search" placeholder="搜索标题或摘要">
      <button type="submit">搜索</button>
      <button v-if="isFiltered" class="quiet-button" type="button" @click="clearFilters">清除</button>
    </form>
    <div class="filter-row">
      <label for="category-filter">分类</label>
      <select id="category-filter" v-model="categoryId" @change="syncFilters">
        <option value="">全部分类</option>
        <option v-for="item in categories" :key="item.id" :value="String(item.id)">{{ item.name }}</option>
      </select>
      <label for="tag-filter">标签</label>
      <select id="tag-filter" v-model="tagId" @change="syncFilters">
        <option value="">全部标签</option>
        <option v-for="item in tags" :key="item.id" :value="String(item.id)">{{ item.name }}</option>
      </select>
    </div>
    <p v-if="pending" class="state">正在读取文章…</p>
    <p v-else-if="!articles.length" class="state">{{ isFiltered ? '没有找到符合条件的文章。' : '还没有发布文章。' }}</p>
    <div v-else class="article-list">
      <NuxtLink v-for="article in articles" :key="article.id" class="article-row" :to="`/article/${article.id}`">
        <span class="article-category">{{ article.categoryName || '随笔' }}</span>
        <span class="article-title">{{ article.title }}</span>
        <time>{{ formatDate(article.createTime) }}</time>
      </NuxtLink>
    </div>
    <nav v-if="total > size" class="pagination" aria-label="文章分页">
      <button class="quiet-button" type="button" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
      <span>{{ page }} / {{ pageCount }} · 共 {{ total }} 篇</span>
      <button class="quiet-button" type="button" :disabled="page >= pageCount" @click="changePage(page + 1)">下一页</button>
    </nav>
  </main>
</template>

<script setup lang="ts">
type Article = { id: number; title: string; summary?: string; categoryName?: string; createTime?: string }
type Option = { id: number; name: string }
const route = useRoute()
const router = useRouter()
const config = useRuntimeConfig()
const keyword = ref(typeof route.query.keyword === 'string' ? route.query.keyword : '')
const categoryId = ref(typeof route.query.categoryId === 'string' ? route.query.categoryId : '')
const tagId = ref(typeof route.query.tagId === 'string' ? route.query.tagId : '')
const year = ref(typeof route.query.year === 'string' ? route.query.year : '')
const month = ref(typeof route.query.month === 'string' ? route.query.month : '')
const page = ref(Math.max(1, Number(route.query.page) || 1))
const size = ref(12)
const filters = computed(() => ({ keyword: keyword.value.trim() || undefined, categoryId: categoryId.value || undefined, tagId: tagId.value || undefined, year: year.value || undefined, month: month.value || undefined }))
const isFiltered = computed(() => Object.values(filters.value).some(Boolean))
const { data: siteSettings } = await useFetch<{ pageSize: number }>(`${config.public.apiBase}/site-settings`)
size.value = Math.min(50, Math.max(1, siteSettings.value?.pageSize ?? 12))
const { data, pending, refresh } = await useFetch<{ records: Article[]; total: number }>(`${config.public.apiBase}/articles`, { query: computed(() => ({ ...filters.value, page: page.value, size: size.value })) })
const articles = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? 0)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const { data: categoriesData } = await useFetch<Option[]>(`${config.public.apiBase}/categories`)
const { data: tagsData } = await useFetch<Option[]>(`${config.public.apiBase}/tags`)
const categories = computed(() => categoriesData.value ?? [])
const tags = computed(() => tagsData.value ?? [])
watch(() => route.query, (query) => {
  keyword.value = typeof query.keyword === 'string' ? query.keyword : ''
  categoryId.value = typeof query.categoryId === 'string' ? query.categoryId : ''
  tagId.value = typeof query.tagId === 'string' ? query.tagId : ''
  year.value = typeof query.year === 'string' ? query.year : ''
  month.value = typeof query.month === 'string' ? query.month : ''
  page.value = Math.max(1, Number(query.page) || 1)
}, { deep: true })

const syncFilters = async () => {
  const query = Object.fromEntries(Object.entries({ ...filters.value, ...(page.value > 1 ? { page: String(page.value) } : {}) }).filter(([, value]) => value))
  await router.replace({ path: '/articles', query })
  await refresh()
}
const applySearch = () => { page.value = 1; return syncFilters() }
const clearFilters = () => { keyword.value = ''; categoryId.value = ''; tagId.value = ''; year.value = ''; month.value = ''; page.value = 1; return syncFilters() }
const changePage = (value: number) => { page.value = value; return syncFilters() }
const formatDate = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN').format(new Date(value)) : ''
</script>
