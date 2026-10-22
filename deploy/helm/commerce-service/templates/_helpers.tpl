{{- define "svc.name" -}}{{ .Release.Name }}{{- end -}}
{{- define "svc.labels" -}}
app.kubernetes.io/name: {{ .Release.Name }}
app.kubernetes.io/part-of: auto-parts-commerce
{{- end -}}
