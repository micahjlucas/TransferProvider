/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.CrossProcessCursor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.CursorWrapper;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.Downloads;
import android.util.Config;
import android.util.Log;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashSet;
import java.util.Map;


/**
 * Allows application to interact with the download manager.
 */
public final class DownloadProvider extends ContentProvider {

    /** Database filename */
    private static final String DB_NAME = "downloads.db";
    /** Current database version */
    private static final int DB_VERSION = 101;
    /** Name of table in the database */
    private static final String DB_TABLE = "downloads";

    /** MIME type for the entire download list */
    private static final String DOWNLOAD_LIST_TYPE = "vnd.android.cursor.dir/download";
    /** MIME type for an individual download */
    private static final String DOWNLOAD_TYPE = "vnd.android.cursor.item/download";

    /** URI matcher used to recognize URIs sent by applications */
    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    /** URI matcher constant for the URI of the entire download list */
    private static final int DOWNLOADS = 1;
    /** URI matcher constant for the URI of an individual download */
    private static final int DOWNLOADS_ID = 2;
    /** URI matcher constant for the URI of a download's request headers */
    private static final int REQUEST_HEADERS_URI = 3;
    static {
        sURIMatcher.addURI("downloads", "download", DOWNLOADS);
        sURIMatcher.addURI("downloads", "download/#", DOWNLOADS_ID);
        sURIMatcher.addURI("downloads", "download/#/" + Downloads.Impl.RequestHeaders.URI_SEGMENT,
                           REQUEST_HEADERS_URI);
    }

    private static final String[] sAppReadableColumnsArray = new String[] {
        Downloads.Impl._ID,
        Downloads.Impl.COLUMN_APP_DATA,
        Downloads.Impl._DATA,
        Downloads.Impl.COLUMN_MIME_TYPE,
        Downloads.Impl.COLUMN_VISIBILITY,
        Downloads.Impl.COLUMN_DESTINATION,
        Downloads.Impl.COLUMN_CONTROL,
        Downloads.Impl.COLUMN_STATUS,
        Downloads.Impl.COLUMN_LAST_MODIFICATION,
        Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE,
        Downloads.Impl.COLUMN_NOTIFICATION_CLASS,
        Downloads.Impl.COLUMN_TOTAL_BYTES,
        Downloads.Impl.COLUMN_CURRENT_BYTES,
        Downloads.Impl.COLUMN_TITLE,
        Downloads.Impl.COLUMN_DESCRIPTION
    };

    private static HashSet<String> sAppReadableColumnsSet;
    static {
        sAppReadableColumnsSet = new HashSet<String>();
        for (int i = 0; i < sAppReadableColumnsArray.length; ++i) {
            sAppReadableColumnsSet.add(sAppReadableColumnsArray[i]);
        }
    }

    /** The database that lies underneath this content provider */
    private SQLiteOpenHelper mOpenHelper = null;

    /** List of uids that can access the downloads */
    private int mSystemUid = -1;
    private int mDefContainerUid = -1;

    @VisibleForTesting
    SystemFacade mSystemFacade;

    /**
     * Creates and updated database on demand when opening it.
     * Helper class to create database the first time the provider is
     * initialized and upgrade it when a new version of the provider needs
     * an updated version of the database.
     */
    private final class DatabaseHelper extends SQLiteOpenHelper {
        public DatabaseHelper(final Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        /**
         * Creates database the first time we try to open it.
         */
        @Override
        public void onCreate(final SQLiteDatabase db) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "populating new database");
            }
            onUpgrade(db, 0, DB_VERSION);
        }

        /**
         * Updates the database format when a content provider is used
         * with a database that was created with a different format.
         *
         * Note: to support downgrades, creating a table should always drop it first if it already
         * exists.
         */
        @Override
        public void onUpgrade(final SQLiteDatabase db, int oldV, final int newV) {
            if (oldV == 31) {
                // 31 and 100 are identical, just in different codelines. Upgrading from 31 is the
                // same as upgrading from 100.
                oldV = 100;
            } else if (oldV < 100) {
                // no logic to upgrade from these older version, just recreate the DB
                Log.i(Constants.TAG, "Upgrading downloads database from version " + oldV
                      + " to version " + newV + ", which will destroy all old data");
                oldV = 99;
            } else if (oldV > newV) {
                // user must have downgraded software; we have no way to know how to downgrade the
                // DB, so just recreate it
                Log.i(Constants.TAG, "Downgrading downloads database from version " + oldV
                      + " (current version is " + newV + "), destroying all old data");
                oldV = 99;
            }

            for (int version = oldV + 1; version <= newV; version++) {
                upgradeTo(db, version);
            }
        }

        /**
         * Upgrade database from (version - 1) to version.
         */
        private void upgradeTo(SQLiteDatabase db, int version) {
            switch (version) {
                case 100:
                    createDownloadsTable(db);
                    break;

                case 101:
                    createHeadersTable(db);
                    break;

                default:
                    throw new IllegalStateException("Don't know how to upgrade to " + version);
            }
        }

        /**
         * Creates the table that'll hold the download information.
         */
        private void createDownloadsTable(SQLiteDatabase db) {
            try {
                db.execSQL("DROP TABLE IF EXISTS " + DB_TABLE);
                db.execSQL("CREATE TABLE " + DB_TABLE + "(" +
                        Downloads.Impl._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                        Downloads.Impl.COLUMN_URI + " TEXT, " +
                        Constants.RETRY_AFTER_X_REDIRECT_COUNT + " INTEGER, " +
                        Downloads.Impl.COLUMN_APP_DATA + " TEXT, " +
                        Downloads.Impl.COLUMN_NO_INTEGRITY + " BOOLEAN, " +
                        Downloads.Impl.COLUMN_FILE_NAME_HINT + " TEXT, " +
                        Constants.OTA_UPDATE + " BOOLEAN, " +
                        Downloads.Impl._DATA + " TEXT, " +
                        Downloads.Impl.COLUMN_MIME_TYPE + " TEXT, " +
                        Downloads.Impl.COLUMN_DESTINATION + " INTEGER, " +
                        Constants.NO_SYSTEM_FILES + " BOOLEAN, " +
                        Downloads.Impl.COLUMN_VISIBILITY + " INTEGER, " +
                        Downloads.Impl.COLUMN_CONTROL + " INTEGER, " +
                        Downloads.Impl.COLUMN_STATUS + " INTEGER, " +
                        Constants.FAILED_CONNECTIONS + " INTEGER, " +
                        Downloads.Impl.COLUMN_LAST_MODIFICATION + " BIGINT, " +
                        Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE + " TEXT, " +
                        Downloads.Impl.COLUMN_NOTIFICATION_CLASS + " TEXT, " +
                        Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS + " TEXT, " +
                        Downloads.Impl.COLUMN_COOKIE_DATA + " TEXT, " +
                        Downloads.Impl.COLUMN_USER_AGENT + " TEXT, " +
                        Downloads.Impl.COLUMN_REFERER + " TEXT, " +
                        Downloads.Impl.COLUMN_TOTAL_BYTES + " INTEGER, " +
                        Downloads.Impl.COLUMN_CURRENT_BYTES + " INTEGER, " +
                        Constants.ETAG + " TEXT, " +
                        Constants.UID + " INTEGER, " +
                        Downloads.Impl.COLUMN_OTHER_UID + " INTEGER, " +
                        Downloads.Impl.COLUMN_TITLE + " TEXT, " +
                        Downloads.Impl.COLUMN_DESCRIPTION + " TEXT, " +
                        Constants.MEDIA_SCANNED + " BOOLEAN);");
            } catch (SQLException ex) {
                Log.e(Constants.TAG, "couldn't create table in downloads database");
                throw ex;
            }
        }

        private void createHeadersTable(SQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS " + Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE);
            db.execSQL("CREATE TABLE " + Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE + "(" +
                       "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                       Downloads.Impl.RequestHeaders.COLUMN_DOWNLOAD_ID + " INTEGER NOT NULL," +
                       Downloads.Impl.RequestHeaders.COLUMN_HEADER + " TEXT NOT NULL," +
                       Downloads.Impl.RequestHeaders.COLUMN_VALUE + " TEXT NOT NULL" +
                       ");");
        }
    }

    /**
     * Initializes the content provider when it is created.
     */
    @Override
    public boolean onCreate() {
        if (mSystemFacade == null) {
            mSystemFacade = new RealSystemFacade(getContext());
        }

        mOpenHelper = new DatabaseHelper(getContext());
        // Initialize the system uid
        mSystemUid = Process.SYSTEM_UID;
        // Initialize the default container uid. Package name hardcoded
        // for now.
        ApplicationInfo appInfo = null;
        try {
            appInfo = getContext().getPackageManager().
                    getApplicationInfo("com.android.defcontainer", 0);
        } catch (NameNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if (appInfo != null) {
            mDefContainerUid = appInfo.uid;
        }
        return true;
    }

    /**
     * Returns the content-provider-style MIME types of the various
     * types accessible through this content provider.
     */
    @Override
    public String getType(final Uri uri) {
        int match = sURIMatcher.match(uri);
        switch (match) {
            case DOWNLOADS: {
                return DOWNLOAD_LIST_TYPE;
            }
            case DOWNLOADS_ID: {
                return DOWNLOAD_TYPE;
            }
            default: {
                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "calling getType on an unknown URI: " + uri);
                }
                throw new IllegalArgumentException("Unknown URI: " + uri);
            }
        }
    }

    /**
     * Inserts a row in the database
     */
    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (sURIMatcher.match(uri) != DOWNLOADS) {
            if (Config.LOGD) {
                Log.d(Constants.TAG, "calling insert on an unknown/invalid URI: " + uri);
            }
            throw new IllegalArgumentException("Unknown/Invalid URI " + uri);
        }

        ContentValues filteredValues = new ContentValues();

        copyString(Downloads.Impl.COLUMN_URI, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_APP_DATA, values, filteredValues);
        copyBoolean(Downloads.Impl.COLUMN_NO_INTEGRITY, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_FILE_NAME_HINT, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_MIME_TYPE, values, filteredValues);
        Integer dest = values.getAsInteger(Downloads.Impl.COLUMN_DESTINATION);
        if (dest != null) {
            if (getContext().checkCallingPermission(Downloads.Impl.PERMISSION_ACCESS_ADVANCED)
                    != PackageManager.PERMISSION_GRANTED
                    && dest != Downloads.Impl.DESTINATION_EXTERNAL
                    && dest != Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE
                    && dest != Downloads.Impl.DESTINATION_FILE_URI) {
                throw new SecurityException("unauthorized destination code");
            }
            if (dest == Downloads.Impl.DESTINATION_FILE_URI) {
                getContext().enforcePermission(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Binder.getCallingPid(), Binder.getCallingUid(),
                        "need WRITE_EXTERNAL_STORAGE permission to use DESTINATION_FILE_URI");
            }
            filteredValues.put(Downloads.Impl.COLUMN_DESTINATION, dest);
        }
        Integer vis = values.getAsInteger(Downloads.Impl.COLUMN_VISIBILITY);
        if (vis == null) {
            if (dest == Downloads.Impl.DESTINATION_EXTERNAL) {
                filteredValues.put(Downloads.Impl.COLUMN_VISIBILITY,
                        Downloads.Impl.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            } else {
                filteredValues.put(Downloads.Impl.COLUMN_VISIBILITY,
                        Downloads.Impl.VISIBILITY_HIDDEN);
            }
        } else {
            filteredValues.put(Downloads.Impl.COLUMN_VISIBILITY, vis);
        }
        copyInteger(Downloads.Impl.COLUMN_CONTROL, values, filteredValues);
        filteredValues.put(Downloads.Impl.COLUMN_STATUS, Downloads.Impl.STATUS_PENDING);
        filteredValues.put(Downloads.Impl.COLUMN_LAST_MODIFICATION,
                           mSystemFacade.currentTimeMillis());
        String pckg = values.getAsString(Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE);
        String clazz = values.getAsString(Downloads.Impl.COLUMN_NOTIFICATION_CLASS);
        if (pckg != null && clazz != null) {
            int uid = Binder.getCallingUid();
            try {
                if (uid == 0 ||
                        getContext().getPackageManager().getApplicationInfo(pckg, 0).uid == uid) {
                    filteredValues.put(Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE, pckg);
                    filteredValues.put(Downloads.Impl.COLUMN_NOTIFICATION_CLASS, clazz);
                }
            } catch (PackageManager.NameNotFoundException ex) {
                /* ignored for now */
            }
        }
        copyString(Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_COOKIE_DATA, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_USER_AGENT, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_REFERER, values, filteredValues);
        if (getContext().checkCallingPermission(Downloads.Impl.PERMISSION_ACCESS_ADVANCED)
                == PackageManager.PERMISSION_GRANTED) {
            copyInteger(Downloads.Impl.COLUMN_OTHER_UID, values, filteredValues);
        }
        filteredValues.put(Constants.UID, Binder.getCallingUid());
        if (Binder.getCallingUid() == 0) {
            copyInteger(Constants.UID, values, filteredValues);
        }
        copyString(Downloads.Impl.COLUMN_TITLE, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_DESCRIPTION, values, filteredValues);

        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "initiating download with UID "
                    + filteredValues.getAsInteger(Constants.UID));
            if (filteredValues.containsKey(Downloads.Impl.COLUMN_OTHER_UID)) {
                Log.v(Constants.TAG, "other UID " +
                        filteredValues.getAsInteger(Downloads.Impl.COLUMN_OTHER_UID));
            }
        }

        Context context = getContext();
        context.startService(new Intent(context, DownloadService.class));

        long rowID = db.insert(DB_TABLE, null, filteredValues);
        insertRequestHeaders(db, rowID, values);

        Uri ret = null;

        if (rowID != -1) {
            context.startService(new Intent(context, DownloadService.class));
            ret = Uri.parse(Downloads.Impl.CONTENT_URI + "/" + rowID);
            context.getContentResolver().notifyChange(uri, null);
        } else {
            if (Config.LOGD) {
                Log.d(Constants.TAG, "couldn't insert into downloads database");
            }
        }

        return ret;
    }

    /**
     * Starts a database query
     */
    @Override
    public Cursor query(final Uri uri, String[] projection,
             final String selection, final String[] selectionArgs,
             final String sort) {

        Helpers.validateSelection(selection, sAppReadableColumnsSet);

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        int match = sURIMatcher.match(uri);
        boolean emptyWhere = true;
        switch (match) {
            case DOWNLOADS: {
                qb.setTables(DB_TABLE);
                break;
            }
            case DOWNLOADS_ID: {
                qb.setTables(DB_TABLE);
                qb.appendWhere(Downloads.Impl._ID + "=");
                qb.appendWhere(getDownloadIdFromUri(uri));
                emptyWhere = false;
                break;
            }
            case REQUEST_HEADERS_URI:
                if (projection != null || selection != null || sort != null) {
                    throw new UnsupportedOperationException("Request header queries do not support "
                                                            + "projections, selections or sorting");
                }
                return queryRequestHeaders(db, uri);
            default: {
                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "querying unknown URI: " + uri);
                }
                throw new IllegalArgumentException("Unknown URI: " + uri);
            }
        }

        if (shouldRestrictVisibility()) {
            boolean canSeeAllExternal;
            if (projection == null) {
                projection = sAppReadableColumnsArray;
                // sAppReadableColumnsArray includes _DATA, which is not allowed
                // to be seen except by the initiating application
                canSeeAllExternal = false;
            } else {
                canSeeAllExternal = getContext().checkCallingPermission(
                        Downloads.Impl.PERMISSION_SEE_ALL_EXTERNAL)
                        == PackageManager.PERMISSION_GRANTED;
                for (int i = 0; i < projection.length; ++i) {
                    if (!sAppReadableColumnsSet.contains(projection[i])) {
                        throw new IllegalArgumentException(
                                "column " + projection[i] + " is not allowed in queries");
                    }
                    canSeeAllExternal = canSeeAllExternal
                            && !projection[i].equals(Downloads.Impl._DATA);
                }
            }
            if (!emptyWhere) {
                qb.appendWhere(" AND ");
                emptyWhere = false;
            }
            String validUid = "( " + Constants.UID + "="
                    + Binder.getCallingUid() + " OR "
                    + Downloads.Impl.COLUMN_OTHER_UID + "="
                    + Binder.getCallingUid() + " )";
            if (canSeeAllExternal) {
                qb.appendWhere("( " + validUid + " OR "
                        + Downloads.Impl.DESTINATION_EXTERNAL + " = "
                        + Downloads.Impl.COLUMN_DESTINATION + " )");
            } else {
                qb.appendWhere(validUid);
            }
        }

        if (Constants.LOGVV) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            sb.append("starting query, database is ");
            if (db != null) {
                sb.append("not ");
            }
            sb.append("null; ");
            if (projection == null) {
                sb.append("projection is null; ");
            } else if (projection.length == 0) {
                sb.append("projection is empty; ");
            } else {
                for (int i = 0; i < projection.length; ++i) {
                    sb.append("projection[");
                    sb.append(i);
                    sb.append("] is ");
                    sb.append(projection[i]);
                    sb.append("; ");
                }
            }
            sb.append("selection is ");
            sb.append(selection);
            sb.append("; ");
            if (selectionArgs == null) {
                sb.append("selectionArgs is null; ");
            } else if (selectionArgs.length == 0) {
                sb.append("selectionArgs is empty; ");
            } else {
                for (int i = 0; i < selectionArgs.length; ++i) {
                    sb.append("selectionArgs[");
                    sb.append(i);
                    sb.append("] is ");
                    sb.append(selectionArgs[i]);
                    sb.append("; ");
                }
            }
            sb.append("sort is ");
            sb.append(sort);
            sb.append(".");
            Log.v(Constants.TAG, sb.toString());
        }

        Cursor ret = qb.query(db, projection, selection, selectionArgs,
                              null, null, sort);

        if (ret != null) {
            ret.setNotificationUri(getContext().getContentResolver(), uri);
            if (Constants.LOGVV) {
                Log.v(Constants.TAG,
                        "created cursor " + ret + " on behalf of " + Binder.getCallingPid());
            }
        } else {
            if (Constants.LOGV) {
                Log.v(Constants.TAG, "query failed in downloads database");
            }
        }

        return ret;
    }

    private String getDownloadIdFromUri(final Uri uri) {
        return uri.getPathSegments().get(1);
    }

    /**
     * Insert request headers for a download into the DB.
     */
    private void insertRequestHeaders(SQLiteDatabase db, long downloadId, ContentValues values) {
        ContentValues rowValues = new ContentValues();
        rowValues.put(Downloads.Impl.RequestHeaders.COLUMN_DOWNLOAD_ID, downloadId);
        for (Map.Entry<String, Object> entry : values.valueSet()) {
            String key = entry.getKey();
            if (key.startsWith(Downloads.Impl.RequestHeaders.INSERT_KEY_PREFIX)) {
                String headerLine = entry.getValue().toString();
                if (!headerLine.contains(":")) {
                    throw new IllegalArgumentException("Invalid HTTP header line: " + headerLine);
                }
                String[] parts = headerLine.split(":", 2);
                rowValues.put(Downloads.Impl.RequestHeaders.COLUMN_HEADER, parts[0].trim());
                rowValues.put(Downloads.Impl.RequestHeaders.COLUMN_VALUE, parts[1].trim());
                db.insert(Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE, null, rowValues);
            }
        }
    }

    /**
     * Handle a query for the custom request headers registered for a download.
     */
    private Cursor queryRequestHeaders(SQLiteDatabase db, Uri uri) {
        String where = Downloads.Impl.RequestHeaders.COLUMN_DOWNLOAD_ID + "="
                       + getDownloadIdFromUri(uri);
        String[] projection = new String[] {Downloads.Impl.RequestHeaders.COLUMN_HEADER,
                                            Downloads.Impl.RequestHeaders.COLUMN_VALUE};
        return db.query(Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE, projection, where,
                        null, null, null, null);
    }

    /**
     * Delete request headers for downloads matching the given query.
     */
    private void deleteRequestHeaders(SQLiteDatabase db, String where, String[] whereArgs) {
        String[] projection = new String[] {Downloads.Impl._ID};
        Cursor cursor = db.query(DB_TABLE, projection , where, whereArgs, null, null, null, null);
        try {
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String idWhere = Downloads.Impl.RequestHeaders.COLUMN_DOWNLOAD_ID + "=" + id;
                db.delete(Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE, idWhere, null);
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * @return true if we should restrict this call to viewing only its own downloads
     */
    private boolean shouldRestrictVisibility() {
        int callingUid = Binder.getCallingUid();
        return Binder.getCallingPid() != Process.myPid() &&
                callingUid != mSystemUid &&
                callingUid != mDefContainerUid &&
                Process.supportsProcesses();
    }

    /**
     * Updates a row in the database
     */
    @Override
    public int update(final Uri uri, final ContentValues values,
            final String where, final String[] whereArgs) {

        Helpers.validateSelection(where, sAppReadableColumnsSet);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        int count;
        long rowId = 0;
        boolean startService = false;

        ContentValues filteredValues;
        if (Binder.getCallingPid() != Process.myPid()) {
            filteredValues = new ContentValues();
            copyString(Downloads.Impl.COLUMN_APP_DATA, values, filteredValues);
            copyInteger(Downloads.Impl.COLUMN_VISIBILITY, values, filteredValues);
            Integer i = values.getAsInteger(Downloads.Impl.COLUMN_CONTROL);
            if (i != null) {
                filteredValues.put(Downloads.Impl.COLUMN_CONTROL, i);
                startService = true;
            }
            copyInteger(Downloads.Impl.COLUMN_CONTROL, values, filteredValues);
            copyString(Downloads.Impl.COLUMN_TITLE, values, filteredValues);
            copyString(Downloads.Impl.COLUMN_DESCRIPTION, values, filteredValues);
        } else {
            filteredValues = values;
            String filename = values.getAsString(Downloads.Impl._DATA);
            if (filename != null) {
                Cursor c = query(uri, new String[]
                        { Downloads.Impl.COLUMN_TITLE }, null, null, null);
                if (!c.moveToFirst() || c.getString(0) == null) {
                    values.put(Downloads.Impl.COLUMN_TITLE,
                            new File(filename).getName());
                }
                c.close();
            }
        }
        int match = sURIMatcher.match(uri);
        switch (match) {
            case DOWNLOADS:
            case DOWNLOADS_ID: {
                String myWhere;
                if (where != null) {
                    if (match == DOWNLOADS) {
                        myWhere = "( " + where + " )";
                    } else {
                        myWhere = "( " + where + " ) AND ";
                    }
                } else {
                    myWhere = "";
                }
                if (match == DOWNLOADS_ID) {
                    String segment = getDownloadIdFromUri(uri);
                    rowId = Long.parseLong(segment);
                    myWhere += " ( " + Downloads.Impl._ID + " = " + rowId + " ) ";
                }
                int callingUid = Binder.getCallingUid();
                if (Binder.getCallingPid() != Process.myPid() &&
                        callingUid != mSystemUid &&
                        callingUid != mDefContainerUid) {
                    myWhere += " AND ( " + Constants.UID + "=" +  Binder.getCallingUid() + " OR "
                            + Downloads.Impl.COLUMN_OTHER_UID + "=" +  Binder.getCallingUid() + " )";
                }
                if (filteredValues.size() > 0) {
                    count = db.update(DB_TABLE, filteredValues, myWhere, whereArgs);
                } else {
                    count = 0;
                }
                break;
            }
            default: {
                if (Config.LOGD) {
                    Log.d(Constants.TAG, "updating unknown/invalid URI: " + uri);
                }
                throw new UnsupportedOperationException("Cannot update URI: " + uri);
            }
        }
        getContext().getContentResolver().notifyChange(uri, null);
        if (startService) {
            Context context = getContext();
            context.startService(new Intent(context, DownloadService.class));
        }
        return count;
    }

    /**
     * Deletes a row in the database
     */
    @Override
    public int delete(final Uri uri, final String where,
            final String[] whereArgs) {

        Helpers.validateSelection(where, sAppReadableColumnsSet);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        int match = sURIMatcher.match(uri);
        switch (match) {
            case DOWNLOADS:
            case DOWNLOADS_ID: {
                String myWhere;
                if (where != null) {
                    if (match == DOWNLOADS) {
                        myWhere = "( " + where + " )";
                    } else {
                        myWhere = "( " + where + " ) AND ";
                    }
                } else {
                    myWhere = "";
                }
                if (match == DOWNLOADS_ID) {
                    String segment = getDownloadIdFromUri(uri);
                    long rowId = Long.parseLong(segment);
                    myWhere += " ( " + Downloads.Impl._ID + " = " + rowId + " ) ";
                }
                int callingUid = Binder.getCallingUid();
                if (Binder.getCallingPid() != Process.myPid() &&
                        callingUid != mSystemUid &&
                        callingUid != mDefContainerUid) {
                    myWhere += " AND ( " + Constants.UID + "=" +  Binder.getCallingUid() + " OR "
                            + Downloads.Impl.COLUMN_OTHER_UID + "="
                            +  Binder.getCallingUid() + " )";
                }
                deleteRequestHeaders(db, where, whereArgs);
                count = db.delete(DB_TABLE, myWhere, whereArgs);
                break;
            }
            default: {
                if (Config.LOGD) {
                    Log.d(Constants.TAG, "deleting unknown/invalid URI: " + uri);
                }
                throw new UnsupportedOperationException("Cannot delete URI: " + uri);
            }
        }
        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    /**
     * Remotely opens a file
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "openFile uri: " + uri + ", mode: " + mode
                    + ", uid: " + Binder.getCallingUid());
            Cursor cursor = query(Downloads.Impl.CONTENT_URI,
                    new String[] { "_id" }, null, null, "_id");
            if (cursor == null) {
                Log.v(Constants.TAG, "null cursor in openFile");
            } else {
                if (!cursor.moveToFirst()) {
                    Log.v(Constants.TAG, "empty cursor in openFile");
                } else {
                    do {
                        Log.v(Constants.TAG, "row " + cursor.getInt(0) + " available");
                    } while(cursor.moveToNext());
                }
                cursor.close();
            }
            cursor = query(uri, new String[] { "_data" }, null, null, null);
            if (cursor == null) {
                Log.v(Constants.TAG, "null cursor in openFile");
            } else {
                if (!cursor.moveToFirst()) {
                    Log.v(Constants.TAG, "empty cursor in openFile");
                } else {
                    String filename = cursor.getString(0);
                    Log.v(Constants.TAG, "filename in openFile: " + filename);
                    if (new java.io.File(filename).isFile()) {
                        Log.v(Constants.TAG, "file exists in openFile");
                    }
                }
               cursor.close();
            }
        }

        // This logic is mostly copied form openFileHelper. If openFileHelper eventually
        //     gets split into small bits (to extract the filename and the modebits),
        //     this code could use the separate bits and be deeply simplified.
        Cursor c = query(uri, new String[]{"_data"}, null, null, null);
        int count = (c != null) ? c.getCount() : 0;
        if (count != 1) {
            // If there is not exactly one result, throw an appropriate exception.
            if (c != null) {
                c.close();
            }
            if (count == 0) {
                throw new FileNotFoundException("No entry for " + uri);
            }
            throw new FileNotFoundException("Multiple items at " + uri);
        }

        c.moveToFirst();
        String path = c.getString(0);
        c.close();
        if (path == null) {
            throw new FileNotFoundException("No filename found.");
        }
        if (!Helpers.isFilenameValid(path)) {
            throw new FileNotFoundException("Invalid filename.");
        }

        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Bad mode for " + uri + ": " + mode);
        }
        ParcelFileDescriptor ret = ParcelFileDescriptor.open(new File(path),
                ParcelFileDescriptor.MODE_READ_ONLY);

        if (ret == null) {
            if (Constants.LOGV) {
                Log.v(Constants.TAG, "couldn't open file");
            }
            throw new FileNotFoundException("couldn't open file");
        } else {
            ContentValues values = new ContentValues();
            values.put(Downloads.Impl.COLUMN_LAST_MODIFICATION, mSystemFacade.currentTimeMillis());
            update(uri, values, null, null);
        }
        return ret;
    }

    private static final void copyInteger(String key, ContentValues from, ContentValues to) {
        Integer i = from.getAsInteger(key);
        if (i != null) {
            to.put(key, i);
        }
    }

    private static final void copyBoolean(String key, ContentValues from, ContentValues to) {
        Boolean b = from.getAsBoolean(key);
        if (b != null) {
            to.put(key, b);
        }
    }

    private static final void copyString(String key, ContentValues from, ContentValues to) {
        String s = from.getAsString(key);
        if (s != null) {
            to.put(key, s);
        }
    }
}
