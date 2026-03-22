import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import zhCN from '../locales/zh-CN/common.json'

const FIXED_LANGUAGE = 'zh-CN'

i18n
  .use(initReactI18next)
  .init({
    resources: {
      'zh-CN': {
        translation: zhCN
      }
    },
    lng: FIXED_LANGUAGE,
    fallbackLng: FIXED_LANGUAGE,
    interpolation: {
      escapeValue: false
    }
  })

export default i18n
