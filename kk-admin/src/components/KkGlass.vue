<script setup lang="ts">
import { computed } from 'vue'
import { LiquidGlass, GlassMode } from '@wxperia/liquid-glass-vue'
import type { CSSProperties } from 'vue'

const props = withDefaults(defineProps<{
  cornerRadius?: number
  padding?: string
  elasticity?: number
  overLight?: boolean
  displacementScale?: number
  blurAmount?: number
  saturation?: number
  aberrationIntensity?: number
  mouseContainer?: HTMLElement | null
  width?: string
  height?: string
  fill?: boolean
}>(), {
  cornerRadius: 24,
  padding: '0px',
  elasticity: 0.12,
  overLight: false,
  displacementScale: 56,
  blurAmount: 0.08,
  saturation: 140,
  aberrationIntensity: 2,
  fill: false,
})

const glassStyle = computed<CSSProperties>(() => ({
  position: 'absolute',
  top: '50%',
  left: '50%',
  width: props.width,
  height: props.height,
}))
</script>

<template>
  <div class="kk-glass-anchor" :class="{ 'kk-glass-fill': fill }">
    <LiquidGlass
      :displacement-scale="displacementScale"
      :blur-amount="blurAmount"
      :saturation="saturation"
      :aberration-intensity="aberrationIntensity"
      :elasticity="elasticity"
      :corner-radius="cornerRadius"
      :padding="padding"
      :over-light="overLight"
      :mouse-container="mouseContainer || undefined"
      :mode="GlassMode.standard"
      :style="glassStyle"
    >
      <slot />
    </LiquidGlass>
  </div>
</template>
