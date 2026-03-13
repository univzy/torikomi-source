/// Shared data contract between the main app and all extension APKs.
/// Keep in sync with lib/models/scrape_result.dart in AIO-Downloader-Dart.
library;

class DownloadItem {
  final String key;
  final String label;
  final String type; // 'video' | 'audio' | 'image'
  final String url;
  final String mimeType;
  final String quality;
  final int? fileSize;
  final Map<String, dynamic> extra;

  const DownloadItem({
    required this.key,
    required this.label,
    required this.type,
    required this.url,
    required this.mimeType,
    this.quality = '',
    this.fileSize,
    this.extra = const {},
  });

  Map<String, dynamic> toJson() => {
        'key': key,
        'label': label,
        'type': type,
        'url': url,
        'mimeType': mimeType,
        'quality': quality,
        if (fileSize != null) 'fileSize': fileSize,
        if (extra.isNotEmpty) 'extra': extra,
      };

  factory DownloadItem.fromJson(Map<String, dynamic> json) => DownloadItem(
        key: json['key'] as String,
        label: json['label'] as String,
        type: json['type'] as String,
        url: json['url'] as String,
        mimeType: json['mimeType'] as String,
        quality: (json['quality'] as String?) ?? '',
        fileSize: json['fileSize'] as int?,
        extra: (json['extra'] as Map<String, dynamic>?) ?? {},
      );
}

class ScrapeResult {
  final String extensionId;
  final String platform;
  final String title;
  final String thumbnail;
  final String author;
  final String authorName;
  final int duration; // seconds
  final List<DownloadItem> downloadItems;
  final List<String> images;
  final String? error;

  const ScrapeResult({
    required this.extensionId,
    required this.platform,
    required this.title,
    this.thumbnail = '',
    this.author = '',
    this.authorName = '',
    this.duration = 0,
    this.downloadItems = const [],
    this.images = const [],
    this.error,
  });

  bool get hasError => error != null && error!.isNotEmpty;
  bool get isSuccess => error == null;

  factory ScrapeResult.error(
          String extensionId, String platform, String message) =>
      ScrapeResult(
        extensionId: extensionId,
        platform: platform,
        title: '',
        error: message,
      );

  Map<String, dynamic> toJson() => {
        'extensionId': extensionId,
        'platform': platform,
        'title': title,
        'thumbnail': thumbnail,
        'author': author,
        if (authorName.isNotEmpty) 'authorName': authorName,
        if (duration > 0) 'duration': duration,
        'downloadItems': downloadItems.map((e) => e.toJson()).toList(),
        'images': images,
        if (error != null) 'error': error,
      };

  factory ScrapeResult.fromJson(Map<String, dynamic> json) => ScrapeResult(
        extensionId: (json['extensionId'] as String?) ?? '',
        platform: (json['platform'] as String?) ?? '',
        title: (json['title'] as String?) ?? '',
        thumbnail: (json['thumbnail'] as String?) ?? '',
        author: (json['author'] as String?) ?? '',
        authorName: (json['authorName'] as String?) ?? '',
        duration: (json['duration'] as int?) ?? 0,
        downloadItems: (json['downloadItems'] as List?)
                ?.map((e) => DownloadItem.fromJson(e as Map<String, dynamic>))
                .toList() ??
            [],
        images:
            (json['images'] as List?)?.map((e) => e as String).toList() ?? [],
        error: json['error'] as String?,
      );
}
                ?.map((e) =>
                    DownloadItem.fromJson(e as Map<String, dynamic>))
                .toList() ??
            [],
        images:
            (json['images'] as List?)?.map((e) => e.toString()).toList() ?? [],
        error: json['error'] as String?,
      );
}
