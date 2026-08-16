<script setup>
import { useRouter } from 'vue-router'
import TasksPage from './TasksPage.vue'

const router = useRouter()
</script>

<template>
  <TasksPage @go-to="router.push($event)" @notify="$emit('notify', $event)" @open-task-modal="$emit('open-task-modal')" />
</template>
