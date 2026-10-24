import type { MouseEvent, RefObject } from 'react';
import type { ItemSummary } from '../types';
import { formatBytes, formatExpiry, formatRelativeTime } from '../utils';
import {
  CheckIcon,
  ChevronRightIcon,
  CloseIcon,
  CopyIcon,
  DownloadIcon,
  FileIcon,
  InboxSparkleIcon,
  ItemTypeIcon,
  LinkIcon,
  RefreshIcon,
  SearchIcon,
  TextIcon,
  TrashIcon,
} from '../Icons';

type InboxListProps = {
  items: ItemSummary[];
  filteredItems: ItemSummary[];
  filterType: 'all' | 'file' | 'url' | 'text';
  setFilterType: (filter: 'all' | 'file' | 'url' | 'text') => void;
  fileCount: number;
  linkCount: number;
  textCount: number;
  searchQuery: string;
  setSearchQuery: (val: string) => void;
  searchInputRef: RefObject<HTMLInputElement | null>;
  listBusy: boolean;
  listError: string;
  inboxNotice: string;
  nextCursor: string | null;
  onRefresh: () => void;
  onLoadMore: () => void;
  onOpenItem: (id: string) => void;
  rowDeleteConfirmId: string | null;
  setRowDeleteConfirmId: (id: string | null) => void;
  copiedItemId: string | null;
  onQuickDownload: (item: ItemSummary, e: MouseEvent) => void;
  onQuickCopy: (item: ItemSummary, e: MouseEvent) => void;
  onQuickDelete: (id: string, e: MouseEvent) => void;
  selectedId?: string | null;
};

export default function InboxList({
  items,
  filteredItems,
  filterType,
  setFilterType,
  fileCount,
  linkCount,
  textCount,
  searchQuery,
  setSearchQuery,
  searchInputRef,
  listBusy,
  listError,
  inboxNotice,
  nextCursor,
  onRefresh,
  onLoadMore,
  onOpenItem,
  rowDeleteConfirmId,
  setRowDeleteConfirmId,
  copiedItemId,
  onQuickDownload,
  onQuickCopy,
  onQuickDelete,
  selectedId,
}: InboxListProps) {
  return (
    <>
      <div className="items-heading">
        <div className="inbox-toolbar-row">
          <div className="inbox-title-wrap">
            <h2>Inbox</h2>
            <span className="inbox-count-badge" aria-label={`${items.length} items`}>
              {items.length}
            </span>
          </div>

          <div className="inbox-search-box">
            <SearchIcon size={14} className="search-icon" />
            <input
              ref={searchInputRef}
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search items…"
              aria-label="Search inbox items"
            />
            {searchQuery ? (
              <button
                type="button"
                className="search-clear-btn"
                onClick={() => {
                  setSearchQuery('');
                  searchInputRef.current?.focus();
                }}
                aria-label="Clear search"
              >
                <CloseIcon size={13} />
              </button>
            ) : (
              <kbd className="search-kbd-hint hide-mobile">/</kbd>
            )}
          </div>

          <button
            className="secondary-button refresh-button"
            type="button"
            onClick={onRefresh}
            disabled={listBusy}
            title="Refresh items list"
            aria-label="Refresh items list"
          >
            <RefreshIcon size={14} className={listBusy ? 'icon-spin' : ''} />
            <span className="hide-mobile">{listBusy ? 'Refreshing…' : 'Refresh'}</span>
          </button>
        </div>
      </div>

      {/* Filter Chips */}
      <div className="filter-bar" role="tablist" aria-label="Filter items by type">
        <button
          type="button"
          role="tab"
          aria-selected={filterType === 'all'}
          className={`filter-chip ${filterType === 'all' ? 'is-active' : ''}`}
          onClick={() => setFilterType('all')}
        >
          <span>All</span>
          <span className="filter-count">{items.length}</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={filterType === 'file'}
          className={`filter-chip ${filterType === 'file' ? 'is-active' : ''}`}
          onClick={() => setFilterType('file')}
        >
          <span className="filter-chip-icon">
            <FileIcon size={13} />
          </span>
          <span>Files</span>
          <span className="filter-count">{fileCount}</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={filterType === 'url'}
          className={`filter-chip ${filterType === 'url' ? 'is-active' : ''}`}
          onClick={() => setFilterType('url')}
        >
          <span className="filter-chip-icon">
            <LinkIcon size={13} />
          </span>
          <span>Links</span>
          <span className="filter-count">{linkCount}</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={filterType === 'text'}
          className={`filter-chip ${filterType === 'text' ? 'is-active' : ''}`}
          onClick={() => setFilterType('text')}
        >
          <span className="filter-chip-icon">
            <TextIcon size={13} />
          </span>
          <span>Text</span>
          <span className="filter-count">{textCount}</span>
        </button>
      </div>

      {/* Notices & Errors */}
      {inboxNotice && (
        <p className="inbox-notice" role="status" aria-live="polite">
          {inboxNotice}
        </p>
      )}
      {listError && (
        <p className="panel-error" role="alert">
          {listError}
        </p>
      )}

      {/* Items List (Dense 52px rows) */}
      <ul className="item-list" aria-label="Inbox items, newest first">
        {filteredItems.map((item) => {
          const isConfirmingDelete = rowDeleteConfirmId === item.id;
          const isCopied = copiedItemId === item.id;
          const isSelected = selectedId === item.id;

          return (
            <li key={item.id}>
              <div className={`item-row ${isSelected ? 'is-selected' : ''}`}>
                <button
                  type="button"
                  className="item-row-main-btn"
                  onClick={() => onOpenItem(item.id)}
                  aria-label={`Open ${item.displayName?.trim() || item.type} details`}
                >
                  <span className={`item-type-badge item-type-badge--${item.type}`} aria-hidden="true">
                    <ItemTypeIcon type={item.type} size={15} />
                  </span>

                  <div className="item-row-content">
                    <div className="item-row-title-line">
                      <strong className="item-title">
                        {item.displayName?.trim() ||
                          (item.type === 'url'
                            ? 'Saved link'
                            : item.type === 'file'
                              ? 'File attachment'
                              : 'Text note')}
                      </strong>
                    </div>
                    <div className="item-row-meta-line font-mono tabular-nums">
                      <span className="device-tag">
                        {item.sourceDevice.platform === 'android' ? 'Android' : 'Web'}
                      </span>
                      <span className="meta-dot">·</span>
                      <span>{formatRelativeTime(item.createdAt)}</span>
                      <span className="meta-dot">·</span>
                      <span>{formatBytes(item.sizeBytes)}</span>
                      <span className="meta-dot">·</span>
                      <span className="expiry-tag">{formatExpiry(item.expiresAt)}</span>
                    </div>
                  </div>
                </button>

                {/* Quick Micro-Actions (non-nested sibling elements) */}
                <div className="item-micro-actions" onClick={(e) => e.stopPropagation()}>
                  {item.type === 'file' ? (
                    <button
                      type="button"
                      tabIndex={0}
                      className="micro-action-btn"
                      onClick={(e) => onQuickDownload(item, e)}
                      title="Download attachment"
                      aria-label="Download attachment"
                    >
                      <DownloadIcon size={14} />
                    </button>
                  ) : (
                    <button
                      type="button"
                      tabIndex={0}
                      className={`micro-action-btn ${isCopied ? 'is-copied' : ''}`}
                      onClick={(e) => void onQuickCopy(item, e)}
                      title={isCopied ? 'Copied!' : 'Copy to clipboard'}
                      aria-label="Copy to clipboard"
                    >
                      {isCopied ? <CheckIcon size={14} /> : <CopyIcon size={14} />}
                    </button>
                  )}

                  {isConfirmingDelete ? (
                    <div className="inline-delete-confirm">
                      <button
                        type="button"
                        className="inline-delete-yes"
                        onClick={(e) => void onQuickDelete(item.id, e)}
                        title="Confirm deletion"
                        aria-label="Confirm deletion"
                      >
                        Delete
                      </button>
                      <button
                        type="button"
                        className="inline-delete-no"
                        onClick={(e) => {
                          e.stopPropagation();
                          setRowDeleteConfirmId(null);
                        }}
                        title="Cancel"
                        aria-label="Cancel"
                      >
                        <CloseIcon size={12} />
                      </button>
                    </div>
                  ) : (
                    <button
                      type="button"
                      tabIndex={0}
                      className="micro-action-btn micro-action-btn--danger"
                      onClick={(e) => void onQuickDelete(item.id, e)}
                      title="Delete item"
                      aria-label="Delete item"
                    >
                      <TrashIcon size={14} />
                    </button>
                  )}

                  <button
                    type="button"
                    tabIndex={0}
                    className="micro-action-btn"
                    onClick={() => onOpenItem(item.id)}
                    title="Open details"
                    aria-label="Open details"
                  >
                    <ChevronRightIcon size={14} />
                  </button>
                </div>
              </div>
            </li>
          );
        })}
      </ul>

      {/* Empty state: clear inbox */}
      {!items.length && !listBusy && (
        <div className="inbox-empty">
          <span className="inbox-empty-icon" aria-hidden="true">
            <InboxSparkleIcon size={36} />
          </span>
          <h3>Your inbox is empty.</h3>
          <p>Add text, a link, or a file from the compose hub to store it securely in your Vault.</p>
        </div>
      )}

      {/* Empty state: filter or search has no matches */}
      {items.length > 0 && !filteredItems.length && !listBusy && (
        <div className="inbox-empty">
          <span className="inbox-empty-icon" aria-hidden="true">
            <InboxSparkleIcon size={36} />
          </span>
          <h3>No matching items found.</h3>
          <p>
            {searchQuery
              ? `No items match "${searchQuery}" in ${filterType === 'all' ? 'your inbox' : `${filterType} items`}.`
              : `There are currently no ${filterType} items in your inbox.`}
          </p>
          <button
            type="button"
            className="secondary-button"
            style={{ marginTop: '12px' }}
            onClick={() => {
              setFilterType('all');
              setSearchQuery('');
            }}
          >
            Reset filters
          </button>
        </div>
      )}

      {/* Skeleton loading indicator */}
      {listBusy && !items.length && (
        <div className="skeleton-container" role="status" aria-label="Loading your items…">
          <div className="skeleton-card" />
          <div className="skeleton-card" />
          <div className="skeleton-card" />
        </div>
      )}

      {/* Load more cursor pagination */}
      {nextCursor && (
        <button
          className="load-more"
          type="button"
          disabled={listBusy}
          onClick={onLoadMore}
        >
          {listBusy ? 'Loading…' : 'Load more items'}
        </button>
      )}
    </>
  );
}
