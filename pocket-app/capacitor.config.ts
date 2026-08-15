import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.profer.pocket.dev',
  appName: 'Profer Pocket（开发版）',
  webDir: 'web',
  android: {
    allowMixedContent: true,
  },
  server: {
    androidScheme: 'https',
    cleartext: true,
  },
}

export default config
