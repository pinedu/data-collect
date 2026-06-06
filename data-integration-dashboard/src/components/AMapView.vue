<template>
  <div ref="mapContainer" class="amap-container"></div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import AMapLoader from '@amap/amap-jsapi-loader'

const props = withDefaults(defineProps<{
  lon: number
  lat: number
  title?: string
}>(), {
  title: '项目位置'
})

const mapContainer = ref<HTMLElement>()
let mapInstance: any = null
let markerInstance: any = null

// 设置高德地图安全密钥（必须在加载 API 前设置）
;(window as any)._AMapSecurityConfig = {
  securityJsCode: '20e9ca473b82b6c3500523ab335f33d9'
}

const initMap = async () => {
  if (!mapContainer.value) return

  const AMap = await AMapLoader.load({
    key: '7f1552cc2d9c57e6aaff5ce789e799c9',
    version: '2.0',
    plugins: ['AMap.Marker', 'AMap.InfoWindow']
  })

  const position = new AMap.LngLat(props.lon, props.lat)

  mapInstance = new AMap.Map(mapContainer.value, {
    zoom: 15,
    center: position,
    viewMode: '2D'
  })

  markerInstance = new AMap.Marker({
    position,
    title: props.title,
    anchor: 'bottom-center'
  })
  mapInstance.add(markerInstance)

  const infoWindow = new AMap.InfoWindow({
    content: `<div style="padding:4px 8px;font-size:13px;">${props.title}</div>`,
    offset: new AMap.Pixel(0, -30)
  })
  infoWindow.open(mapInstance, position)
}

const destroyMap = () => {
  if (mapInstance) {
    mapInstance.destroy()
    mapInstance = null
    markerInstance = null
  }
}

watch(
  () => [props.lon, props.lat],
  ([newLon, newLat]) => {
    if (mapInstance && newLon && newLat) {
      const AMap = (window as any).AMap
      const position = new AMap.LngLat(newLon, newLat)
      mapInstance.setCenter(position)
      mapInstance.setZoom(15)
      if (markerInstance) {
        markerInstance.setPosition(position)
      }
    }
  }
)

onMounted(initMap)
onBeforeUnmount(destroyMap)
</script>

<style scoped>
.amap-container {
  width: 100%;
  height: 100%;
  min-height: 400px;
}
</style>
