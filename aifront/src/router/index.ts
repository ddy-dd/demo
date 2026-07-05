import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior() {
    // 路由切换/刷新时默认滚动到底部（配合 main.ts 中 scrollRestoration = 'manual'）
    return { top: 999999 }
  },
  routes: [
    {
      path: '/',
      redirect: '/chat',
    },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/components/SimpleChat.vue'),
    },
    {
      path: '/calling',
      name: 'calling',
      component: () => import('@/components/CallingPage.vue'),
    },
  ],
})

export default router
