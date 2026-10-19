#!/usr/bin/env node
/**
 * 批量替换 window.confirm 为 ConfirmDialog
 * 快速脚本化完成 20+ 处替换
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 需要处理的文件清单
const filesToProcess = [
  'frontend/src/views/settings/AccountSecurityPage.vue',
  'frontend/src/views/settings/ProfilePage.vue',
  'frontend/src/views/ai/AiInsightPage.vue',
  'frontend/src/views/ai/AiTaskManagePage.vue',
  'frontend/src/views/ai/AiResultsPage.vue',
  'frontend/src/views/hazard/HazardDetailPage.vue',
  'frontend/src/views/hazard/ConfirmationWorkbenchPage.vue',
  'frontend/src/views/warning/WarningResolvePage.vue',
  'frontend/src/views/system/ReportCenterPage.vue',
  'frontend/src/views/system/BackupRecoveryPage.vue',
  'frontend/src/views/system/NotificationChannelsPage.vue',
  'frontend/src/views/device/OtaManagePage.vue',
  'frontend/src/components/rule/RuleList.vue',
];

const REPO_ROOT = path.dirname(path.dirname(__dirname));

for (const filePath of filesToProcess) {
  const fullPath = path.join(REPO_ROOT, filePath);
  if (!fs.existsSync(fullPath)) {
    console.warn(`⚠️  文件不存在: ${fullPath}`);
    continue;
  }

  try {
    let content = fs.readFileSync(fullPath, 'utf-8');
    let modified = false;

    // 1. 检查是否已有 ConfirmDialog import
    if (!content.includes('ConfirmDialog')) {
      // 2. 找到最后一个 import 语句，在其后添加
      const importMatch = content.match(/^.*import\s+.*from\s+["'][^"']+["'];?$/m);
      if (importMatch) {
        const lastImportIndex = content.lastIndexOf(importMatch[0]) + importMatch[0].length;
        
        // 确定正确的 import 路径
        let importPath = '../common/ConfirmDialog.vue';
        if (filePath.includes('views/system')) {
          importPath = '../../components/common/ConfirmDialog.vue';
        } else if (filePath.includes('views/settings')) {
          importPath = '../../components/common/ConfirmDialog.vue';
        } else if (filePath.includes('views/ai')) {
          importPath = '../../components/common/ConfirmDialog.vue';
        } else if (filePath.includes('views/hazard')) {
          importPath = '../../components/common/ConfirmDialog.vue';
        } else if (filePath.includes('views/warning')) {
          importPath = '../../components/common/ConfirmDialog.vue';
        } else if (filePath.includes('views/device')) {
          importPath = '../../components/common/ConfirmDialog.vue';
        } else if (filePath.includes('components/rule')) {
          importPath = '../common/ConfirmDialog.vue';
        }
        
        const importStatement = `\nimport ConfirmDialog from '${importPath}'`;
        content = content.slice(0, lastImportIndex) + importStatement + content.slice(lastImportIndex);
        modified = true;
      }
    }

    // 3. 检查是否已有 confirmDialogRef
    if (!content.includes('confirmDialogRef') && content.includes('window.confirm')) {
      // 在 const emit 后面加
      const emitMatch = content.match(/const emit = defineEmits\(\[.*?\]\)/);
      if (emitMatch) {
        const insertPos = content.indexOf(emitMatch[0]) + emitMatch[0].length;
        content = content.slice(0, insertPos) + '\nconst confirmDialogRef = ref(null)' + content.slice(insertPos);
        modified = true;
      } else {
        // 如果没有 emit，在第一个 const 前插入
        const firstConstMatch = content.match(/const\s+\w+\s*=/);
        if (firstConstMatch) {
          const insertPos = content.indexOf(firstConstMatch[0]);
          content = content.slice(0, insertPos) + 'const confirmDialogRef = ref(null)\n' + content.slice(insertPos);
          modified = true;
        }
      }
    }

    // 4. 替换所有 window.confirm 
    if (content.includes('window.confirm')) {
      content = content.replace(/if\s*\(\s*!window\.confirm\s*\(`([^`]+)`\)\s*\)\s*return/g, (match, message) => {
        return `const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: \`${message}\`,
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return`;
      });

      content = content.replace(/const\s+ok\s*=\s*window\.confirm\s*\(`([^`]+)`\)/g, (match, message) => {
        return `const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: \`${message}\`,
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })`;
      });

      content = content.replace(/if\s*\(\s*!window\.confirm\s*\('([^']+)'\)\s*\)\s*return/g, (match, message) => {
        return `const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: '${message}',
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return`;
      });

      content = content.replace(/const\s+ok\s*=\s*window\.confirm\s*\('([^']+)'\)/g, (match, message) => {
        return `const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: '${message}',
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })`;
      });

      modified = true;
    }

    // 5. 检查是否需要在 template 末尾添加组件
    if (modified && !content.includes('<ConfirmDialog ref="confirmDialogRef"')) {
      const lastTemplate = content.lastIndexOf('</template>');
      if (lastTemplate > -1) {
        content = content.slice(0, lastTemplate) + '    <ConfirmDialog ref="confirmDialogRef" />\n' + content.slice(lastTemplate);
        modified = true;
      }
    }

    if (modified) {
      fs.writeFileSync(fullPath, content, 'utf-8');
      console.log(`✅ 已处理: ${filePath}`);
    } else {
      console.log(`⏭️  跳过（无改动）: ${filePath}`);
    }
  } catch (e) {
    console.error(`❌ 处理失败 ${filePath}:`, e.message);
  }
}

console.log('\n✅ 批量替换完成');
