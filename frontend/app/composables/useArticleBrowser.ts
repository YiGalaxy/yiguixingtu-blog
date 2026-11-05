import type { SiteSettings } from '~/utils/siteTypes'
import type { ArticleSummary, ArticleTag } from '~/utils/articleTypes'

export const useArticleBrowser = (home = false) => {
  const route = useRoute()
  const router = useRouter()
  const config = useRuntimeConfig()
  const text = (key: string) => typeof route.query[key] === 'string' ? route.query[key] as string : ''
  const keyword = ref(text('keyword'))
  const appliedKeyword = computed(() => text('keyword'))
  const categoryId = computed(() => text('categoryId'))
  const tagId = computed(() => text('tagId'))
  const page = computed(() => Math.max(1, Number(text('page')) || 1))
  const settingsRequest = useFetch<SiteSettings>(`${config.public.apiBase}/site-settings`)
  const { data: settings } = settingsRequest
  const limit = ref(settings.value?.pageSize || 6)
  watch(settings, value => { if (home && value) limit.value = value.pageSize || 6 })
  const size = computed(() => home ? limit.value : Math.min(50, Math.max(1, Number(text('size')) || settings.value?.pageSize || 6)))
  const query = computed(() => ({ keyword: appliedKeyword.value || undefined, categoryId: categoryId.value || undefined,
    tagId: tagId.value || undefined, year: text('year') || undefined, month: text('month') || undefined,
    page: home ? 1 : page.value, size: size.value }))
  type ArticlePage = { records: ArticleSummary[]; total: number }
  const articleRequest = home
    ? useAsyncData<ArticlePage>('home-article-browser', async () => {
        const requested = size.value
        const chunkSize = Math.min(50, requested)
        const filters = { ...query.value, size: chunkSize }
        const first = await $fetch<ArticlePage>(`${config.public.apiBase}/articles`, { query: { ...filters, page: 1 } })
        const pages = Math.ceil(Math.min(first.total, requested) / chunkSize)
        const rest = await Promise.all(Array.from({ length: Math.max(0, pages - 1) }, (_, i) =>
          $fetch<ArticlePage>(`${config.public.apiBase}/articles`, { query: { ...filters, page: i + 2 } })))
        return { total: first.total, records: [first, ...rest].flatMap(p => p.records).slice(0, requested) }
      }, { watch: [query] })
    : useFetch<ArticlePage>(`${config.public.apiBase}/articles`, { query })
  const { data, pending, error, refresh } = articleRequest
  const categoryRequest = useFetch<ArticleTag[]>(`${config.public.apiBase}/categories`)
  const tagRequest = useFetch<ArticleTag[]>(`${config.public.apiBase}/tags`)
  const { data: categories } = categoryRequest
  const { data: tags } = tagRequest
  const ready = Promise.all([settingsRequest, articleRequest, categoryRequest, tagRequest])
  const articles = computed(() => data.value?.records ?? [])
  const total = computed(() => data.value?.total ?? 0)
  const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
  const isFiltered = computed(() => !!(appliedKeyword.value || categoryId.value || tagId.value || text('year') || text('month')))
  const title = computed(() => appliedKeyword.value ? `搜索：${appliedKeyword.value}` : categoryId.value
    ? categories.value?.find(c => String(c.id) === categoryId.value)?.name || '分类文章'
    : tagId.value ? `#${tags.value?.find(t => String(t.id) === tagId.value)?.name || '标签文章'}` : '最新文章')
  const update = (values: Record<string, string | undefined>) => {
    if (home) limit.value = settings.value?.pageSize || 6
    const next = Object.fromEntries(Object.entries({ ...route.query, ...values }).filter(([, value]) => value != null && value !== ''))
    return router.push({ path: home ? '/' : '/articles', query: next })
  }
  const search = () => update({ keyword: keyword.value.trim() || undefined, page: undefined })
  const selectCategory = (value: string) => update({ categoryId: value || undefined, page: undefined })
  const selectTag = (value: string) => update({ tagId: value === tagId.value ? undefined : value, page: undefined })
  const changePage = (value: number) => update({ page: value > 1 ? String(value) : undefined })
  const changeSize = (value: number) => update({ size: String(value), page: undefined })
  let timer: ReturnType<typeof setTimeout> | undefined
  watch(keyword, () => {
    clearTimeout(timer)
    if (keyword.value.trim() !== appliedKeyword.value) timer = setTimeout(search, 300)
  })
  watch(appliedKeyword, value => { keyword.value = value })
  onBeforeUnmount(() => clearTimeout(timer))
  const clear = () => {
    clearTimeout(timer)
    keyword.value = ''
    return update({ keyword: undefined, categoryId: undefined, tagId: undefined, year: undefined, month: undefined, page: undefined })
  }
  const loadMore = () => { limit.value += settings.value?.pageSize || 6 }
  return { ready, keyword, categoryId, tagId, page, size, settings, articles, total, pending, error, refresh,
    categories, tags, pageCount, isFiltered, title, search, selectCategory, selectTag, changePage, changeSize, clear, loadMore }
}
