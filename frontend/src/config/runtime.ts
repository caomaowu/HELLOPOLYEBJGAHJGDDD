const FALSE_VALUES = new Set(['0', 'false', 'no', 'off'])

const parseBooleanEnv = (value: string | undefined, defaultValue: boolean): boolean => {
  if (!value) {
    return defaultValue
  }
  return !FALSE_VALUES.has(value.trim().toLowerCase())
}

export const isSystemUpdateEnabled = (): boolean =>
  parseBooleanEnv(import.meta.env.VITE_ENABLE_SYSTEM_UPDATE, false)
