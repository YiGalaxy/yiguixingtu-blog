<template>
  <aside class="article-catalog" :class="{ 'is-open': open, 'is-borderless': borderless }" aria-label="文章目录">
    <div class="catalog-toolbar"><button type="button" class="catalog-toggle" :aria-expanded="open" aria-controls="catalog-links" @click="open = !open"><span>☷ {{ open ? '文章目录' : '目录' }}</span><small>{{ headings.length }}</small><span>{{ open ? '−' : '+' }}</span></button><button v-if="open" type="button" class="catalog-border-toggle" :aria-pressed="borderless" @click="borderless = !borderless">{{ borderless ? '显示边框' : '隐藏边框' }}</button></div>
    <nav v-show="open" id="catalog-links" class="catalog-nav" aria-label="文章章节"><ol><li v-for="heading in headings" :key="heading.id"><a :href="`#${heading.id}`" :title="heading.text" :style="{ paddingLeft: `${12 + (heading.level - minLevel) * 12}px` }" :aria-current="activeId === heading.id ? 'location' : undefined" @click="jump($event, heading.id)">{{ heading.text }}</a></li></ol></nav>
  </aside>
</template>
<script setup lang="ts">
import type { ArticleHeading } from '~/utils/markdown'
const props = defineProps<{ headings: ArticleHeading[] }>()
const open = ref(false)
const borderless = ref(false)
const activeId = ref('')
const minLevel = computed(() => Math.min(...props.headings.map(h => h.level)))
let viewport: MediaQueryList | undefined
let frame = 0
const updateActive = () => {
  frame = 0
  let current = props.headings[0]?.id || ''
  for (const heading of props.headings) if ((document.getElementById(heading.id)?.getBoundingClientRect().top ?? Infinity) <= 104) current = heading.id
  activeId.value = current
}
const schedule = () => { if (!frame) frame = requestAnimationFrame(updateActive) }
const adapt = () => { open.value = viewport?.matches ?? false }
const jump = (event: MouseEvent, id: string) => {
  if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
  const element = document.getElementById(id)
  if (!element) return
  event.preventDefault()
  if (!viewport?.matches) open.value = false
  element.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' })
  element.setAttribute('tabindex', '-1'); element.focus({ preventScroll: true })
  history.replaceState(history.state, '', `#${id}`)
  activeId.value = id
}
watch(() => props.headings, () => nextTick(schedule))
onMounted(() => { viewport = matchMedia('(min-width: 1440px)'); adapt(); viewport.addEventListener('change', adapt); window.addEventListener('scroll', schedule, { passive: true }); window.addEventListener('resize', schedule); schedule() })
onBeforeUnmount(() => { viewport?.removeEventListener('change', adapt); window.removeEventListener('scroll', schedule); window.removeEventListener('resize', schedule); cancelAnimationFrame(frame) })
</script>
