import { watch, onBeforeUnmount } from "vue";

/**
 * 锁定/解锁 document.body 的滚动。
 * 用法：在弹窗打开的 ref 上 watch：
 *   const showModal = ref(false);
 *   useBodyScrollLock(showModal);
 * 关闭弹窗会自动恢复；组件卸载也会清理一次以防状态卡住。
 *
 * 实现细节：
 * - 给 body 设 overflow: hidden，禁止页面级滚动
 * - 用 padding-right 补偿滚动条宽度，避免内容抖动
 */
export function useBodyScrollLock(activeRef) {
  const lock = () => {
    if (typeof document === "undefined") return;
    const body = document.body;
    if (body.dataset.scrollLocked === "1") return;
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
    body.dataset.scrollLocked = "1";
    body.dataset.scrollLockPrevPadding = body.style.paddingRight;
    body.dataset.scrollLockPrevOverflow = body.style.overflow;
    if (scrollbarWidth > 0) {
      body.style.paddingRight = `${scrollbarWidth}px`;
    }
    body.style.overflow = "hidden";
  };

  const unlock = () => {
    if (typeof document === "undefined") return;
    const body = document.body;
    if (body.dataset.scrollLocked !== "1") return;
    body.style.overflow = body.dataset.scrollLockPrevOverflow || "";
    body.style.paddingRight = body.dataset.scrollLockPrevPadding || "";
    delete body.dataset.scrollLocked;
    delete body.dataset.scrollLockPrevPadding;
    delete body.dataset.scrollLockPrevOverflow;
  };

  watch(activeRef, (active) => {
    if (active) lock();
    else unlock();
  });

  // 卸载时保险解锁一次，避免热更新或路由切换后页面无法滚动
  onBeforeUnmount(unlock);
}

export default useBodyScrollLock;
