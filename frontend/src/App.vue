<template>
  <MainLayout v-if="showLayout" />
  <router-view v-else />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import MainLayout from '@/components/layout/MainLayout.vue'

const route = useRoute()
const showLayout = computed(() => route.path !== '/login')
</script>
