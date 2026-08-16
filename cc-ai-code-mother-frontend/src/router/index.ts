import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
    },
    // {
    //   path: '/about',
    //   name: 'about',
    //   // route level code-splitting
    //   // this generates a separate chunk (About.[hash].js) for this route
    //   // which is lazy-loaded when the route is visited. 需懒加​​​​载组件 优化⁠⁠⁠⁠首次​​​​​​​​打开站点性能的方式
    //   component: () => import('../pages/AboutView.vue'),
    // },
  ],
})

export default router
