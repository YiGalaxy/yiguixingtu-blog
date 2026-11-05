<template>
  <main class="page">
    <header class="page-heading"><p class="eyebrow">LISTENING ROOM</p><h1>音乐</h1><p>站长整理的曲目。</p></header>
    <p v-if="pending" class="state">正在读取曲目…</p>
    <p v-else-if="!tracks?.length" class="state">还没有添加曲目。</p>
    <section v-else class="track-list">
      <article v-for="track in tracks" :key="track.id" class="track-row">
        <div><h2>{{ track.title }}</h2><p>{{ track.artist || '未知艺术家' }}</p></div>
        <audio :src="track.audioUrl" :aria-label="`播放 ${track.title}`" controls preload="none" />
        <details v-if="track.lyrics"><summary>歌词</summary><pre>{{ track.lyrics }}</pre></details>
      </article>
    </section>
  </main>
</template>

<script setup lang="ts">
const config = useRuntimeConfig()
const { data: tracks, pending } = await useFetch<Array<{ id: number; title: string; artist?: string; audioUrl: string; lyrics?: string }>>(`${config.public.apiBase}/music`)
</script>
