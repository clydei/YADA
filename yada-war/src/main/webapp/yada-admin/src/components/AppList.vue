<template>
  <div
    id="app-list"
    class="ui relaxed divided list">
    <AppListItem
      v-for="app in apps"
      :key="app.APP"
      :app="app.APP" />
  </div>
</template>

<script>
import AppListItem from './AppListItem.vue'
import * as types from '../store/vuex-types'
import { mapState } from 'vuex'
export default {
  components: { AppListItem },
  name: 'AppList',
  data () {
    return {
    }
  },
  methods: {
    login () {
      const YADA = JSON.parse(window.sessionStorage.getItem('YADA'))
      this.$store.commit(types.SET_LOGGEDUSER, atob(YADA.sec.u))
      this.$store.dispatch(types.LOAD_APPS, {})
    }
  },
  mounted () {
    this.$nextTick(() => this.login())
  },
  computed: {
    ...mapState(['loggeduser', 'apps', 'app'])
  },
  watch: {

  }
}
</script>

<style scoped>
#app-list div.applistitem:first-child {
  padding-top: 7px;
  margin-top: 15px;
}
</style>
