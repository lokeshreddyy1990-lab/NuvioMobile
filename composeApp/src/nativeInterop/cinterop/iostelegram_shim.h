#include <stdint.h>

int32_t NuvioTelegramStart(int32_t api_id, const char *api_hash, const char *app_version);
char *NuvioTelegramRequest(const char *json, double timeout_seconds);
char *NuvioTelegramPlaybackURL(
    int32_t file_id,
    int64_t file_size,
    const char *file_name,
    const char *mime_type
);
char *NuvioTelegramVirtualPlaybackURL(const char *spec_json);
char *NuvioTelegramReadConcat(
    const char *parts_json,
    int64_t offset,
    int32_t length,
    int32_t *out_length
);
int64_t NuvioTelegramCacheSize(void);
void NuvioTelegramClearCache(void);
void NuvioTelegramOptimizeCache(void);
void NuvioTelegramFree(char *value);
