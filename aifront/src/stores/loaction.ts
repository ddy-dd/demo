import { defineStore } from "pinia";
import { computed, ref } from "vue";

/**
 * 用户地理位置信息
 */
export interface userLocation {
  latitude: number;
  longitude: number;
}

/**
 * 地理位置状态管理
 *
 * 缓存用户的地理位置信息，避免重复请求浏览器定位权限。
 * 当 AI 需要位置信息时（GetUserLocationTool），由此 store 提供缓存或发起新定位请求。
 */
export const locationStore = defineStore('location', () => {
  const currentLocation = ref<userLocation | null>(null);

  /** 是否已有位置信息 */
  const hasLocation = computed(() => currentLocation.value !== null);

  function setLocation(location: userLocation) {
    currentLocation.value = location;
  }

  function clearLocation() {
    currentLocation.value = null;
  }

  function getLocation(): userLocation | null {
    return currentLocation.value;
  }

  return {
    currentLocation,
    hasLocation,
    setLocation,
    clearLocation,
    getLocation,
  };
});
