<template>
  <nav class="article-pagination" aria-label="文章分页">
    <div class="pagination-settings"><span>共 <strong>{{ total }}</strong> 篇</span><label>每页显示<select :value="size" aria-label="每页文章数" @change="$emit('size', Number(($event.target as HTMLSelectElement).value))"><option v-for="n in sizes" :key="n" :value="n">{{ n }} 篇</option></select></label></div>
    <div class="page-buttons"><button type="button" :disabled="page <= 1" aria-label="上一页" @click="$emit('page', page - 1)">‹</button>
      <template v-for="(n, i) in pages" :key="i"><span v-if="n === 0" class="page-ellipsis">…</span><button v-else type="button" :class="{ selected: page === n }" :aria-current="page === n ? 'page' : undefined" @click="$emit('page', n)">{{ n }}</button></template>
      <button type="button" :disabled="page >= count" aria-label="下一页" @click="$emit('page', page + 1)">›</button>
    </div>
  </nav>
</template>
<script setup lang="ts">
const props = defineProps<{ total: number; size: number; page: number; count: number }>()
defineEmits<{ page: [value: number]; size: [value: number] }>()
const sizes = computed(() => [...new Set([6, 12, 24, 48, props.size])].sort((a,b) => a-b))
const pages = computed(() => {
  const result: number[] = []
  for (let n = 1; n <= props.count; n++) {
    if (props.count <= 7 || n === 1 || n === props.count || Math.abs(n - props.page) <= 1) result.push(n)
    else if (result[result.length - 1] !== 0) result.push(0)
  }
  return result
})
</script>
