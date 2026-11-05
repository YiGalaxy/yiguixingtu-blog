<template>
  <main class="page login-page">
    <form class="login-form" @submit.prevent="login">
      <p class="eyebrow">ADMINISTRATOR</p>
      <h1>管理员登录</h1>
      <label>账号<input v-model="username" autocomplete="username" required maxlength="64"></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required maxlength="200"></label>
      <p v-if="error" class="login-error" role="alert">{{ error }}</p>
      <button type="submit" :disabled="busy">{{ busy ? '正在登录…' : '登录后台' }}</button>
    </form>
  </main>
</template>

<script setup lang="ts">
const config = useRuntimeConfig()
const route = useRoute()
const router = useRouter()
const token = useCookie<string | null>('blog-admin-token', { sameSite: 'lax', secure: import.meta.env.PROD })
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

const login = async () => {
  error.value = ''
  busy.value = true
  try {
    const result = await $fetch<{ token: string }>('/auth/login', {
      baseURL: config.public.apiBase,
      method: 'POST',
      body: { username: username.value, password: password.value },
    })
    token.value = result.token
    await router.replace(typeof route.query.redirect === 'string' ? route.query.redirect : '/admin')
  } catch {
    error.value = '账号或密码错误，请检查后重试。'
  } finally {
    busy.value = false
  }
}
</script>
