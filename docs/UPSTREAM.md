# Sincronización con upstream

Base: https://github.com/jqssun/android-airplay-server (GPL-3.0). Historial completo conservado.

```bash
git remote add upstream https://github.com/jqssun/android-airplay-server   # una vez
git fetch upstream
git checkout -b sync/upstream-$(date +%Y%m%d)
git merge upstream/main
git submodule update --init --recursive
./gradlew assembleDebug
```

Reglas:
- Los cambios propios van en commits pequeños y separados para poder proponerlos upstream.
- No modificar `app/src/main/cpp/third_party/` directamente: usar parches en `app/src/main/cpp/patches/`.
- Mantener `LICENSE` (GPL-3.0) y los créditos originales.
