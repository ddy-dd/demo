import {defineStore} from "pinia";
import {computed, ref} from "vue";



export interface userLocation {
    latitude: number;
    longitude: number;
}

export const locationStore = defineStore('location',() =>{
    const currentLocation = ref<userLocation | null>(null)

    const hasLocation = computed(() => currentLocation.value !== null);

    function setLocation(location: userLocation) {
        currentLocation.value = location;
    }
    function clearLocation() {
        currentLocation.value = null;
    }
    function getLocation() {
        return currentLocation.value;
    }
    return {
        currentLocation,
        hasLocation,
        setLocation,
        clearLocation,
        getLocation
    }

})