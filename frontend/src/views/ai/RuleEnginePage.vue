<script setup>
/**
 * 规则引擎管理页面
 * 独立页面入口：/dashboard/settings/rule-engine
 * 也可作为 Settings 页面的 Tab
 */
import { computed, onMounted, ref } from "vue"
import { Settings as Gear, Plus } from "lucide-vue-next"
import RuleList from "../../components/rule/RuleList.vue"
import RuleEditor from "../../components/rule/RuleEditor.vue"
import RuleTestPanel from "../../components/rule/RuleTestPanel.vue"

const emit = defineEmits(["notify"])

const view = ref("list") // list | edit | test
const editingRule = ref(null)
const testingRule = ref(null)

// 显示编辑
function handleEdit(rule) {
  editingRule.value = rule
  view.value = "edit"
}

function handleCreate() {
  editingRule.value = null
  view.value = "edit"
}

// 保存后
function handleSave() {
  view.value = "list"
}

function handleDuplicate(rule) {
  editingRule.value = rule
  view.value = "edit"
}

function handleTest(rule) {
  testingRule.value = rule
  view.value = "test"
}

// 返回列表
function backToList() {
  view.value = "list"
  editingRule.value = null
  testingRule.value = null
}
</script>

<template>
  <div class="rule-engine-page">
    <header class="page-header" v-if="view === 'list'">
      <div>
      </div>
      <div class="actions">
        <button class="primary-btn" @click="handleCreate">
          <Plus :size="14" />
          新建规则
        </button>
      </div>
    </header>

    <!-- 列表视图 -->
    <RuleList
      v-if="view === 'list'"
      @edit="handleEdit"
      @duplicate="handleDuplicate"
      @notify="(msg) => emit('notify', msg)"
    />

    <!-- 编辑视图 -->
    <div v-else-if="view === 'edit'" class="edit-view">
      <div class="edit-toolbar">
        <button class="back-btn" @click="backToList">← 返回规则列表</button>
      </div>
      <RuleEditor
        :rule="editingRule"
        @save="handleSave"
        @cancel="backToList"
        @test="handleTest"
        @notify="(msg) => emit('notify', msg)"
      />
    </div>

    <!-- 测试视图 -->
    <div v-else-if="view === 'test'" class="test-view">
      <div class="edit-toolbar">
        <button class="back-btn" @click="backToList">← 返回规则列表</button>
      </div>
      <RuleTestPanel
        v-if="testingRule"
        :rule="testingRule"
        @close="backToList"
      />
    </div>
  </div>
</template>

<style scoped>
.rule-engine-page {
  padding: 24px 28px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 18px;
  gap: 12px;
  flex-wrap: wrap;
}

.page-header h2 {
  margin: 0;
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 18px;
  color: #1f3e44;
}

.page-header p {
  margin: 4px 0 0;
  color: #7a8d89;
  font-size: 12px;
}

.edit-view,
.test-view {
  max-width: 900px;
}

.edit-toolbar {
  margin-bottom: 16px;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border: 1px solid #d9e6e1;
  background: #fff;
  border-radius: 6px;
  color: #37555a;
  font-size: 13px;
  cursor: pointer;
}

.back-btn:hover {
  background: #f4f7f5;
}

.primary-btn {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 8px 14px;
  border: 0;
  background: #2f7f80;
  color: #fff;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}
</style>
