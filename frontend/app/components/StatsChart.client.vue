<template><div ref="element" class="stats-chart" role="img" :aria-label="label" :style="{ height }" /></template>
<script setup lang="ts">
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
const props = withDefaults(defineProps<{ option: EChartsCoreOption; label: string; height?: string }>(), { height: '280px' })
const element = useTemplateRef<HTMLDivElement>('element')
let chart: ECharts | undefined
let observer: ResizeObserver | undefined
onMounted(() => {
  if (!element.value) return
  chart = init(element.value)
  chart.setOption(props.option)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(element.value)
})
watch(() => props.option, value => chart?.setOption(value, true), { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<style scoped>.stats-chart { width: 100%; min-width: 0; }</style>
