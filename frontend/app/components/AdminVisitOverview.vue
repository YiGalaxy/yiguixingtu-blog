<template>
  <h2>概览</h2><p class="muted">全站汇总数据（文章数与浏览量只统计已发布文章）</p>
  <div class="overview-stats"><div v-for="item in counters" :key="item.label"><span>{{ item.label }}</span><strong>{{ summaryFailed ? '—' : item.value }}</strong></div></div>
  <section class="visit-panel pixel-panel">
    <div class="visit-head"><h3>访问统计</h3><div class="visit-actions"><button class="quiet-button" type="button" :disabled="!visits?.totalPv || loading" @click="openIps">访客 IP</button><button v-for="range in [7, 30, 90]" :key="range" class="quiet-button" :aria-pressed="days === range" type="button" @click="days = range">近 {{ range }} 天</button></div></div>
    <p v-if="loading" class="state">访问数据加载中…</p>
    <p v-else-if="failed" class="state">访问数据读取失败。<button class="quiet-button" @click="loadVisits">重试</button></p>
    <p v-else-if="!visits?.totalPv" class="state">这段时间还没有访问记录。等有人来过之后这里就会出现曲线。</p>
    <template v-else>
      <div class="visit-numbers"><div><span>访问次数（PV）</span><strong>{{ visits.totalPv }}</strong></div><div><span>独立访客（UV）</span><strong>{{ visits.totalUv }}</strong></div><div><span>其中首次来访</span><strong>{{ visits.newVisitors }}</strong><em>占 {{ Math.round(visits.newVisitors / Math.max(1, visits.totalUv) * 100) }}%</em></div></div>
      <ClientOnly><StatsChart :option="dailyOption" label="每天的访问次数与独立访客趋势" /></ClientOnly>
      <div class="visit-grid"><div v-for="group in groups" :key="group.title" class="visit-block"><h4>{{ group.title }}</h4><ClientOnly><StatsChart :option="pieOption(group.values)" :label="group.title" height="220px" /></ClientOnly></div></div>
      <div class="visit-grid visit-ranks"><div class="visit-block"><h4>访问最多的页面</h4><ul><li v-for="item in visits.topPaths" :key="item.key"><span :title="item.key">{{ item.key }}</span><b>{{ item.value }}</b></li></ul></div><div class="visit-block"><h4>访问最多的文章</h4><ul><li v-for="item in visits.topArticles" :key="item.id"><NuxtLink v-if="item.title" :to="`/article/${item.id}`" :title="item.title">{{ item.title }}</NuxtLink><span v-else>（文章已删除）</span><b>{{ item.pv }}</b></li><li v-if="!visits.topArticles.length">这段时间还没有文章访问记录。</li></ul></div></div>
      <p class="visit-note">按 UTC 日期统计，保留最近 90 天记录；UV 按 IP 去重，首次来访按保留期内最早记录估计。同一 IP 在 5 秒内重复打开同一页面只记录一次。</p>
    </template>
  </section>
  <section class="visit-panel pixel-panel"><h3>最近文章</h3><p v-if="recentFailed" class="state">最近文章读取失败。</p><ul v-else class="recent-articles"><li v-for="article in recent" :key="article.id"><span>{{ article.title }}</span><span class="muted">{{ article.status ? '已发布' : '草稿' }} · {{ article.createTime?.slice(0, 16).replace('T', ' ') }}</span></li><li v-if="!recent.length" class="state">还没有文章，去「文章管理」写第一篇吧。</li></ul></section>
  <dialog ref="ipDialog" class="visit-dialog pixel-panel" aria-label="访客 IP">
    <div class="visit-head"><h3>访客 IP</h3><button class="quiet-button" @click="ipDialog?.close()">关闭</button></div>
    <p v-if="ipsLoading" class="state">访客数据加载中…</p><p v-else-if="ipsFailed" class="state">访客数据读取失败。</p>
    <div v-else class="ip-table"><table><thead><tr><th>IP</th><th>归属地</th><th>访问次数</th><th>最后访问（UTC）</th></tr></thead><tbody><tr v-for="row in ips" :key="row.ip"><td>{{ row.ip }}</td><td>{{ row.region || '—' }}</td><td>{{ row.visits }}</td><td>{{ row.lastVisit }}</td></tr></tbody></table></div>
    <p class="visit-note">按访问次数排序，最多显示 200 个 IP。当前未接入归属地数据库，归属地显示「—」。原始访问记录超过 90 天后自动清理。</p>
  </dialog>
</template>
<script setup lang="ts">
import type { EChartsCoreOption } from 'echarts/core'
type Slice = { key: string; value: number }
type Report = { totalPv: number; totalUv: number; newVisitors: number; daily: { date: string; pv: number; uv: number }[]; visitorTypes: Slice[]; refererTypes: Slice[]; devices: Slice[]; topPaths: Slice[]; topArticles: { id: number; title: string | null; pv: number }[] }
const config = useRuntimeConfig()
const token = useCookie<string | null>('blog-admin-token')
const call = <T>(path: string, query?: Record<string, number>) => $fetch<T>(path, { baseURL: config.public.apiBase, query, headers: { Authorization: `Bearer ${token.value}` } })
const summary = ref({ articleCount: 0, viewCount: 0, categoryCount: 0 })
const counters = computed(() => [{ label: '文章（已发布）', value: summary.value.articleCount }, { label: '浏览量', value: summary.value.viewCount }, { label: '分类', value: summary.value.categoryCount }])
const summaryFailed = ref(false)
const recentFailed = ref(false)
const recent = ref<{ id: number; title: string; status: number; createTime: string }[]>([])
const days = ref(30)
const visits = ref<Report | null>(null)
const loading = ref(false)
const failed = ref(false)
let revision = 0
const loadVisits = async () => {
  const request = ++revision
  loading.value = true; failed.value = false
  try { const data = await call<Report>('/admin/stats/visits', { days: days.value }); if (request === revision) visits.value = data }
  catch { if (request === revision) { visits.value = null; failed.value = true } }
  finally { if (request === revision) loading.value = false }
}
watch(days, loadVisits)
onMounted(async () => {
  await Promise.allSettled([
    call<typeof summary.value>('/stats').then(data => { summary.value = data }).catch(() => { summaryFailed.value = true }),
    call<{ records: typeof recent.value }>('/admin/articles', { size: 5 }).then(data => { recent.value = data.records }).catch(() => { recentFailed.value = true }),
    loadVisits(),
  ])
})
const colors = ['#f2c14e', '#7fb2f0', '#6ee7b7', '#f0868a', '#b78af0']
const labels: Record<string, string> = { GUEST: '游客', ADMIN: '管理员', PC: '电脑', MOBILE: '手机', DIRECT: '直接访问', INTERNAL: '站内跳转', EXTERNAL: '外链' }
const theme = useState<'night' | 'day'>('site-theme', () => 'night')
const textColor = computed(() => theme.value === 'night' ? '#8fa3bf' : '#4d624b')
const dailyOption = computed<EChartsCoreOption>(() => ({
  color: colors, tooltip: { trigger: 'axis', renderMode: 'richText' }, legend: { top: 0, data: ['访问次数', '独立访客'], textStyle: { color: textColor.value } },
  grid: { left: 45, right: 20, top: 45, bottom: 35 },
  xAxis: { type: 'category', data: visits.value?.daily.map(point => point.date.slice(5)), axisLabel: { color: textColor.value, hideOverlap: true } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: textColor.value }, splitLine: { lineStyle: { color: 'rgba(150,190,240,.15)' } } },
  series: [{ name: '访问次数', type: 'line', smooth: true, areaStyle: { opacity: .08 }, data: visits.value?.daily.map(point => point.pv) }, { name: '独立访客', type: 'line', smooth: true, data: visits.value?.daily.map(point => point.uv) }],
}))
const groups = computed(() => [{ title: '访客身份', values: visits.value?.visitorTypes || [] }, { title: '来源', values: visits.value?.refererTypes || [] }, { title: '设备', values: visits.value?.devices || [] }])
const pieOption = (values: Slice[]): EChartsCoreOption => ({ color: colors, tooltip: { trigger: 'item', renderMode: 'richText' }, legend: { bottom: 0, textStyle: { color: textColor.value } }, series: [{ type: 'pie', radius: ['42%', '68%'], center: ['50%', '43%'], label: { show: false }, data: values.map(item => ({ name: labels[item.key] || item.key, value: item.value })) }] })
const ipDialog = useTemplateRef<HTMLDialogElement>('ipDialog')
const ips = ref<{ ip: string; region: string | null; visits: number; lastVisit: string }[]>([])
const ipsLoading = ref(false)
const ipsFailed = ref(false)
const openIps = async () => {
  ipDialog.value?.showModal(); ipsLoading.value = true; ipsFailed.value = false
  try { ips.value = await call<typeof ips.value>('/admin/stats/visits/ips', { days: days.value }) }
  catch { ips.value = []; ipsFailed.value = true }
  finally { ipsLoading.value = false }
}
</script>
<style scoped>
.visit-panel { margin-top: 24px; padding: 20px; min-width: 0; }
h3, h4 { margin: 0 0 12px; }
.visit-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.visit-actions { display: flex; gap: 4px; flex-wrap: wrap; }
.visit-actions button[aria-pressed="true"] { border-color: var(--accent); color: var(--accent); }
.visit-numbers { display: flex; gap: 32px; margin: 20px 0; flex-wrap: wrap; }
.visit-numbers div { display: grid; gap: 4px; }
.visit-numbers span, .visit-note { color: var(--muted); font-size: 12px; }
.visit-numbers strong { font: 700 28px ui-monospace, monospace; color: var(--accent); }
.visit-numbers em { font-size: 12px; color: var(--muted); font-style: normal; }
.visit-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-top: 18px; }
.visit-block { min-width: 0; border: 1px solid var(--line); padding: 14px; }
.visit-block h4 { font-size: 14px; }
.visit-ranks { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.visit-ranks ul, .recent-articles { list-style: none; padding: 0; margin: 0; }
.visit-ranks li, .recent-articles li { display: flex; justify-content: space-between; gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--line); font-size: 13px; }
.visit-ranks li > :first-child { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.visit-ranks b { color: var(--accent); }
.visit-dialog { width: min(720px, 92vw); max-height: 85vh; padding: 22px; color: var(--ink); background: var(--panel); border: 2px solid var(--line); }
.visit-dialog::backdrop { background: rgba(0,0,0,.65); }
.ip-table { overflow-x: auto; margin-top: 15px; }
table { border-collapse: collapse; width: 100%; font-size: 13px; white-space: nowrap; }
td, th { padding: 10px; text-align: left; border-bottom: 1px solid var(--line); }
@media(max-width:700px) { .visit-panel { padding: 12px; } .visit-grid { grid-template-columns: 1fr; } .recent-articles li { flex-direction: column; gap: 4px; } .overview-stats { flex-wrap: wrap; } }
</style>
