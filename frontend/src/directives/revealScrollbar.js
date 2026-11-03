/**
 * v-reveal-scrollbar — 滚动条按需显隐（add-scrollbar-reveal，design D1/D2）
 *
 * 语义（与工作台卡片 personal-workbench D8 逐字一致）：
 * - 默认隐藏：CSS 控制（styles/reveal-scrollbar.css，轨道留位滑块透明）
 * - 显示：指针在元素内（mouseenter 门控）且元素发生滚动（scroll 单事件覆盖
 *   滚轮/触屏/键盘/程序化，有真实位移才亮）
 * - 隐去：停滚 1s（悬停也隐）；mouseenter→mouseleave 立即隐
 *
 * 用法：v-reveal-scrollbar（作用于组件时落到其根元素，如 el-main → <main>）。
 */
const HIDE_DELAY_MS = 1000

export default {
  mounted(el) {
    let hovering = false
    let timer = null

    const hide = () => {
      clearTimeout(timer)
      timer = null
      el.removeAttribute('data-reveal-on')
    }
    const onScroll = () => {
      if (!hovering) return // 指针不在元素内的滚动（程序化等）不亮条
      el.setAttribute('data-reveal-on', '')
      clearTimeout(timer)
      timer = setTimeout(hide, HIDE_DELAY_MS)
    }
    const onEnter = () => { hovering = true }
    const onLeave = () => { hovering = false; hide() }

    el.setAttribute('data-reveal-scrollbar', '')
    // scroll 不冒泡，直接挂目标元素；passive 不影响显隐判定
    el.addEventListener('scroll', onScroll, { passive: true })
    el.addEventListener('mouseenter', onEnter)
    el.addEventListener('mouseleave', onLeave)

    el._revealScrollbarCleanup = () => {
      clearTimeout(timer)
      el.removeEventListener('scroll', onScroll)
      el.removeEventListener('mouseenter', onEnter)
      el.removeEventListener('mouseleave', onLeave)
      el.removeAttribute('data-reveal-scrollbar')
      el.removeAttribute('data-reveal-on')
    }
  },
  unmounted(el) {
    el._revealScrollbarCleanup?.()
    delete el._revealScrollbarCleanup
  },
}
