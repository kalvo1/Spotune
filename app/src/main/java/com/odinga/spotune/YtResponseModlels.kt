package com.odinga.spotune

import kotlinx.serialization.Serializable

@Serializable
data class TopContents (
    val singleColumnBrowseResultsRenderer: SingleColumnBrowseResultsRenderer? = null,
    val twoColumnBrowseResultsRenderer: TwoColumnBrowseResultsRenderer? = null,
    val singleColumnMusicWatchNextResultsRenderer: SingleColumnMusicWatchNextResultsRenderer? = null,
    val tabbedSearchResultsRenderer: TabbedRenderer? = null
)

@Serializable
data class HeaderRenderer (
    val menu: Menu? = null,
    val startRadioButton: Button? = null,
    val subscriptionButton: Button? = null,
    val thumbnail: Thumbnail? = null,
    val title: Text? = null,
    val moreContentButton: ButtonRenderer? = null
)

@Serializable
data class Button (
    val buttonRenderer: ButtonRenderer? = null,
    val subscribeButtonRenderer: ButtonRenderer? = null,
)

@Serializable
data class ButtonRenderer (
    val navigationEndpoint: NavigationEndpoint? = null,
    val channelId: String? = null,
    val subscriberCountText: Text? = null,
    val text: Text? = null
)

@Serializable
data class ContinuationContents (
    val playlistPanelContinuation: PlaylistPanelRenderer? = null,
    val musicShelfContinuation: MusicShelfRenderer? = null
)

@Serializable
data class OnResponseReceivedAction (
    val appendContinuationItemsAction: AppendContinuationItemsAction? = null
)

@Serializable
data class AppendContinuationItemsAction (
    val continuationItems: ArrayList<MusicShelfRendererContent> = arrayListOf()
)

@Serializable
data class SingleColumnMusicWatchNextResultsRenderer (
    val tabbedRenderer: TabbedRenderer? = null
)

@Serializable
data class TabbedRenderer (
    val watchNextTabbedResultsRenderer: WatchNextTabbedResultsRenderer? = null,
    val tabs: ArrayList<Tab> = arrayListOf()
)

@Serializable
data class WatchNextTabbedResultsRenderer (
    val tabs: ArrayList<Tab> = arrayListOf()
)

@Serializable
data class SingleColumnBrowseResultsRenderer (
     val tabs: ArrayList<Tab> = arrayListOf()
)

@Serializable
data class TwoColumnBrowseResultsRenderer (
    val secondaryContents: SecondaryContents? = null,
    val tabs: ArrayList<Tab>? = arrayListOf()
)

@Serializable
data class SecondaryContents (
    val sectionListRenderer: SectionListRenderer? = null,
)

@Serializable
data class Tab (
    val tabRenderer: TabRenderer? = null
)

@Serializable
data class TabRenderer (
    val content: TabContent? = null,
    val title: String? = null
)

@Serializable
data class TabContent (
    val sectionListRenderer: SectionListRenderer? = null,
    val musicQueueRenderer: MusicQueueRenderer? = null
)

@Serializable
data class MusicQueueRenderer (
    val content: MusicQueueContent? = null,
    val header: MusicQueueHeader? = null,
    val subHeaderChipCloud: SubHeaderChipCloud? = null
)

@Serializable
data class SubHeaderChipCloud (
    val chipCloudRenderer: ChipCloudRenderer? = null
)

@Serializable
data class ChipCloudRenderer (
    val chips: ArrayList<Chip> = arrayListOf()
)

@Serializable
data class Chip (
    val chipCloudChipRenderer: ChipCloudChipRenderer? = null
)

@Serializable
data class ChipCloudChipRenderer (
    val text: Text? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
    val uniqueId: String? = null
)

@Serializable
data class MusicQueueHeader (
    val musicQueueHeaderRenderer: MusicQueueHeaderRenderer? = null
)

@Serializable
data class MusicQueueHeaderRenderer (
    val subtitle: Text? = null
)

@Serializable
data class MusicQueueContent (
    val playlistPanelRenderer: PlaylistPanelRenderer? = null
)

@Serializable
data class PlaylistPanelRenderer (
    val contents: ArrayList<PlaylistPanelContent> = arrayListOf(),
    val continuations: ArrayList<Continuation> = arrayListOf(),
    val isInfinite: Boolean = false,
    val playlistId: String? = null
)

@Serializable
data class Continuation (
    val nextRadioContinuationData: ContinuationData? = null,
    val nextContinuationData: ContinuationData? = null,
)

@Serializable
data class ContinuationData (
    val continuation: String? = null,
    val clickTrackingParams: String? = null,
)

@Serializable
data class SectionListRenderer (
    val contents: ArrayList<SectionListContent> = arrayListOf(),
    val header: Header? = null
)

@Serializable
data class Header (
    val chipCloudRenderer: ChipCloudRenderer? = null,
    val musicImmersiveHeaderRenderer: HeaderRenderer? = null,
    val musicCarouselShelfBasicHeaderRenderer: HeaderRenderer? = null
)

@Serializable
data class PlaylistPanelContent (
    val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer? = null
)

@Serializable
data class PlaylistPanelVideoRenderer (
    val badges: ArrayList<Badge> = arrayListOf(),
    val videoId: String? = null,
    val thumbnail: ThumbUrls? = null,
    val lengthText: Text? = null,
    val longBylineText: Text? = null,
    val menu: Menu? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
    val shortBylineText: Text? = null,
    val title: Text? = null
)

@Serializable
data class SectionListContent (
    val musicShelfRenderer: MusicShelfRenderer? = null,
    val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer? = null,
    val musicResponsiveHeaderRenderer: MusicResponsiveHeaderRenderer? = null,
    val musicCardShelfRenderer: MusicCardShelfRenderer? = null,
    val itemSectionRenderer: ItemSectionRenderer? = null,
    val musicCarouselShelfRenderer: MusicCarouselShelfRenderer? = null
)

@Serializable
data class MusicCarouselShelfRenderer (
    val contents: ArrayList<MusicCarouselShelfContent> = arrayListOf(),
    val header: Header? = null
)

@Serializable
data class MusicCarouselShelfContent (
    val musicTwoRowItemRenderer: MusicTwoRowItemRenderer? = null
)

@Serializable
data class MusicTwoRowItemRenderer (
    val menu: Menu? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
    val subtitle: Text? = null,
    val thumbnailRenderer: Thumbnail? = null,
    val title: Text? = null
)

@Serializable
data class ItemSectionRenderer (
    val contents: ArrayList<ItemSectionRendererContent> = arrayListOf(),
)

@Serializable
data class ItemSectionRendererContent (
    val musicShelfRenderer: MusicShelfRenderer? = null,
    val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
)

@Serializable
data class MusicCardShelfRenderer (
    val title: Text? = null,
    val thumbnail: Thumbnail? = null,
    val subtitle: Text? = null,
    val menu: Menu? = null,
    val onTap: OnTap? = null,
)

@Serializable
data class OnTap (
    val browseEndpoint: BrowseEndpoint? = null
)

@Serializable
data class MusicResponsiveHeaderRenderer (
    val thumbnail: Thumbnail? = null,
    val title: Text? = null,
    val subtitle: Text? = null,
    val secondSubtitle: Text? = null,
    val straplineTextOne: Text? = null,
    val description: Description? = null,
)

@Serializable
data class Description (
    val musicDescriptionShelfRenderer: MusicDescriptionShelfRenderer? = null
)

@Serializable
data class MusicDescriptionShelfRenderer (
    val description: Text? = null
)

@Serializable
data class Thumbnail (
    val musicThumbnailRenderer: MusicThumbnailRenderer? = null
)

@Serializable
data class MusicThumbnailRenderer (
    val thumbnail: ThumbUrls? = null
)

@Serializable
data class ThumbUrls (
    val thumbnails: ArrayList<TrackThumbnail> = arrayListOf()
)

@Serializable
data class TrackThumbnail (
    val height: Double? = null,
    val width: Double? = null,
    val url: String? = null
)

@Serializable
data class MusicShelfRenderer (
    val contents: ArrayList<MusicShelfRendererContent> = arrayListOf(),
    val title: Text? = null,
    val continuations: ArrayList<Continuation> = arrayListOf(),
    val bottomEndpoint: NavigationEndpoint? = null
)

@Serializable
data class MusicPlaylistShelfRenderer (
    var contents: ArrayList<MusicShelfRendererContent> = arrayListOf()
)

@Serializable
data class MusicShelfRendererContent (
    val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
    val continuationItemRenderer: ContinuationItemRenderer? = null,
)

@Serializable
data class ContinuationItemRenderer (
    val continuationEndpoint: ContinuationEndpoint? = null
)

@Serializable
data class ContinuationEndpoint (
    val continuationCommand: ContinuationCommand? = null
)

@Serializable
data class ContinuationCommand (
    val request: String? = null,
    val token: String? = null
)

@Serializable
data class MusicResponsiveListItemRenderer (
    val badges: ArrayList<Badge> = arrayListOf(),
    val fixedColumns: ArrayList<FixedColumn> = arrayListOf(),
    val flexColumns: ArrayList<FlexColumn> = arrayListOf(),
    val index: Text? = null,
    val menu: Menu? = null,
    val thumbnail: Thumbnail? = null,
    val navigationEndpoint: NavigationEndpoint? = null
)

@Serializable
data class Badge (
    val musicInlineBadgeRenderer: MusicInlineBadgeRenderer? = null
)

@Serializable
data class MusicInlineBadgeRenderer (
    val icon: Icon? = null
)

@Serializable
data class FixedColumn (
    val musicResponsiveListItemFixedColumnRenderer: MusicResponsiveListItemFixedColumnRenderer? = null
)

@Serializable
data class FlexColumn (
    val musicResponsiveListItemFlexColumnRenderer: MusicResponsiveListItemFlexColumnRenderer? = null
)

@Serializable
data class MusicResponsiveListItemFixedColumnRenderer (
    val text: Text? = null,
)

@Serializable
data class MusicResponsiveListItemFlexColumnRenderer (
    val text: Text? = null,
)

@Serializable
data class Text (
    val runs: ArrayList<Run> = arrayListOf(),
)

@Serializable
data class Menu (
    val menuRenderer: MenuRenderer? = null
)

@Serializable
data class  MenuRenderer (
    val items: ArrayList<MenuItem> = arrayListOf()
)

@Serializable
data class MenuItem (
    val menuNavigationItemRenderer: MenuNavigationItemRenderer? = null
)

@Serializable
data class MenuNavigationItemRenderer (
    val icon: Icon? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
    val text: Text? = null
)

@Serializable
data class Icon (
    val iconType: String? = null
)

@Serializable
data class Run (
    val navigationEndpoint: NavigationEndpoint? = null,
    val text: String? = null
)

@Serializable
data class NavigationEndpoint (
    val watchEndpoint: WatchEndpoint? = null,
    val browseEndpoint: BrowseEndpoint? = null,
    val queueUpdateCommand: QueueUpdateCommand? = null,
    val clickTrackingParams: String? = null,
    val urlEndpoint: UrlEndpoint? = null,
    val searchEndpoint: Endpoint? = null
)

@Serializable
data class Endpoint (
    val params: String? = null,
    val query: String? = null
)

@Serializable
data class QueueUpdateCommand (
    val fetchContentsCommand: FetchContentsCommand? = null,
)

@Serializable
data class FetchContentsCommand (
    val watchEndpoint: WatchEndpoint? = null
)

@Serializable
data class WatchEndpoint (
    val index: Int? = null,
    val videoId: String? = null,
    val playlistId: String? = null,
    val params: String? = null,
    val playerParams: String? = null,
    val playlistSetVideoId: String? = null,
    val loggingContext: LoggingContext? = null,
    val watchEndpointMusicSupportedConfigs: WatchEndpointMusicSupportedConfigs? = null
)

@Serializable
data class LoggingContext (
    val vssLoggingContext: VssLoggingContext? = null
)

@Serializable
data class VssLoggingContext (
    val serializedContextData: String? = null
)

@Serializable
data class WatchEndpointMusicSupportedConfigs (
    val watchEndpointMusicConfig: WatchEndpointMusicConfig? = null
)

@Serializable
data class WatchEndpointMusicConfig (
    val hasPersistentPlaylistPanel: Boolean? = true,
    val musicVideoType: String? = null
)

@Serializable
data class BrowseEndpoint (
    val browseEndpointContextSupportedConfigs: BrowseEndpointContextSupportedConfigs? = null,
    val browseId: String? = null,
    val params: String? = null
)

@Serializable
data class UrlEndpoint(
    val url: String? = null,
    val target: String? = null,
)

@Serializable
data class BrowseEndpointContextSupportedConfigs (
    val browseEndpointContextMusicConfig: BrowseEndpointContextMusicConfig? = null
)

@Serializable
data class BrowseEndpointContextMusicConfig (
    val pageType: String? = null
)

