{{- define "lob-engine.name" -}}{{ .Release.Name }}-lob-engine{{- end -}}
{{- define "lob-engine.labels" -}}
app.kubernetes.io/name: lob-engine
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
