-- This file is part of Doppel.

-- Doppel is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- Doppel is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2026 akano12

-- Photos and videos keep where they were taken, which tells where someone lives, works and goes. Android removes
-- the location of photos for apps without ACCESS_MEDIA_LOCATION; apps with it get the original file.
-- ExifInterface of Android, of AndroidX and of the support library gives no GPS tags (like a photo without them),
-- hasAttribute is false for a GPS tag and getAttributeRange gives nothing for it, and the XMP of the photo has its
-- GPS properties blanked, like the media provider does; tags the app set on the object itself stay, and so do tags
-- the media provider zeroed already. MediaMetadataRetriever gives no location of videos. The location columns of
-- MediaStore (latitude, longitude), which Android 10 and later leave empty, are empty on Android 8.1 and 9 too, also
-- to selections and sort orders, so the provider answers with its own cursor like on Android 10.
-- Not covered are apps reading the bytes of photos themselves, and the range of the XMP (getAttributeRange).
local METADATA_KEY_LOCATION = 23
local COLUMNS = { 'latitude', 'longitude' }
local NULL = {}
local MODE_ALLOWED = 0
local PERMISSION_GRANTED = 0
local FLAG_GRANT_READ_URI_PERMISSION = 1

-- Words of SQL after which a name is a value, like latitude in AND latitude; after other words (like AS), names,
-- values and ) a name is an alias or a type, like la in latitude la and latitude AS la
local OPERATORS = { SELECT = true, DISTINCT = true, ALL = true, WHERE = true, AND = true, OR = true, NOT = true,
                    IS = true, LIKE = true, GLOB = true, MATCH = true, REGEXP = true, BETWEEN = true, ESCAPE = true,
                    CASE = true, WHEN = true, THEN = true, ELSE = true, BY = true, ON = true, HAVING = true,
                    COLLATE = true }
-- Words which are values themselves, never an alias, like NULL in latitude IS NULL
local VALUES = { NULL = true, END = true, ISNULL = true, NOTNULL = true, CURRENT_DATE = true, CURRENT_TIME = true,
                 CURRENT_TIMESTAMP = true }
-- Words which end the FROM of a single table, like WHERE in FROM files AS f WHERE
local ENDS = { WHERE = true, GROUP = true, ORDER = true, LIMIT = true, UNION = true, EXCEPT = true, INTERSECT = true }
-- The uris of the tables with the location columns, after the volume, for the columns of a * of them
local TABLES = { files = 'file', images = 'images/media', video = 'video/media' }

-- Whether before made the query of the media provider in progress on this thread give no location; else after
-- hides the location columns behind a proxy, like when the columns of the table could not be read
local rewritten = false
-- The projection and Bundle of the app, when before changed them, for after when SQLite refuses the changed SQL
local asked = nil

-- Whether a uri is of the media provider, also with the user in front, like 0@media (see contentresolver_query)
function media(uri)
    local authority = (uri ~= nil and uri:getAuthority() or nil)
    return authority ~= nil and string.gsub(authority, '^.*@', '') == 'media'
end

-- The location column of a name of SQL, in any case, like latitude for Latitude, else nil
function located(name)
    name = string.lower(name)
    for _, column in ipairs(COLUMNS) do
        if name == column then
            return column
        end
    end
    return nil
end

-- The tokens of a text of SQL, each with its kind, its text, the spaces and comments before it, and where its text
-- starts and ends: names (word), quoted names, strings, numbers, variables, other characters and the end
function tokenize(sql)
    local tokens = {}
    local index = 1
    while true do
        local start = index
        while true do
            local _, e = string.find(sql, '^%s+', index)
            if e == nil then
                _, e = string.find(sql, '^%-%-[^\n]*', index)
            end
            if e == nil and string.sub(sql, index, index + 1) == '/*' then
                _, e = string.find(sql, '*/', index + 2, true)
                e = e or #sql
            end
            if e == nil then
                break
            end
            index = e + 1
        end
        local token = { space = string.sub(sql, start, index - 1), first = index }
        local c = string.sub(sql, index, index)
        local e
        if c == '' then
            token.kind, e = 'end', index - 1
        elseif string.find(c, '[%a_\128-\255]') then
            token.kind, e = 'word', select(2, string.find(sql, '^[%w_$\128-\255]+', index))
        elseif string.find(sql, '^%.?%d', index) then
            token.kind, e = 'number', select(2, string.find(sql, '^%.?[%w.]+', index))
        elseif c == '?' then
            -- Digits only, so ?AND is ? and AND (CC_VARNUM of tokenize.c)
            token.kind, e = 'variable', select(2, string.find(sql, '^%?%d*', index))
        elseif string.find(c, '[:@$]') then
            token.kind, e = 'variable', select(2, string.find(sql, '^.[%w_$]*', index))
        elseif string.find(c, '["`[\']') then
            -- Up to the closing quote, of which a doubled one is inside
            local close = (c == '[' and ']' or c)
            local doubled
            token.kind, e = (c == "'" and 'string' or 'quoted'), index
            repeat
                e = string.find(sql, close, e + 1, true) or #sql
                doubled = (close ~= ']' and string.sub(sql, e + 1, e + 1) == close)
                if doubled then
                    e = e + 1
                end
            until not doubled
        else
            token.kind, e = 'char', index
        end
        token.text, token.last = string.sub(sql, index, e), e
        table.insert(tokens, token)
        if token.kind == 'end' then
            return tokens
        end
        index = e + 1
    end
end

-- Whether a name after a token is an alias or a type, like la in latitude la and latitude AS la
function alias(before, token)
    if before == nil or token.column ~= nil or (token.kind == 'word' and VALUES[string.upper(token.text)]) or
            (token.kind ~= 'word' and token.kind ~= 'quoted' and token.kind ~= 'string') then
        return false
    end
    if before.kind == 'word' then
        return not OPERATORS[string.upper(before.text)]
    end
    return before.kind ~= 'char' or before.text == ')'
end

-- Ends a column of a projection: one of a location column alone keeps its name (NULLIF(latitude, latitude) AS
-- latitude), and so does an expression of one without an alias, which SQLite names by its text, like latitude+0; both
-- up to a FROM of its own, as SQLite names the columns of a compound by its first SELECT, like latitude FROM files
-- UNION SELECT _id
function finish(sql, out, first, star, items)
    local last, stop, depth = #out, #out, 0
    for index = first, last do
        local text = string.upper(out[index].text)
        depth = depth + (text == '(' and 1 or (text == ')' and -1 or 0))
        if depth == 0 and text == 'FROM' and out[index].kind == 'word' then
            stop = index - 1
            break
        end
    end
    local values, replaced = {}, false
    for index = first, stop do
        if out[index].text ~= '(' and out[index].text ~= ')' then
            table.insert(values, out[index])
            replaced = replaced or out[index].hidden
        end
    end
    if replaced then
        if #values == 1 and values[1].column ~= nil then
            out[first].text = values[1].text .. ' AS ' .. values[1].column
            for index = first + 1, stop do
                out[index].text, out[index].space = '', ''
            end
        elseif not (#values == 1 and values[1].expanded) and not alias(out[stop - 1], out[stop]) then
            local name = string.sub(sql, out[first].first, out[stop].last)
            out[stop].text = out[stop].text .. ' AS "' .. string.gsub(name, '"', '""') .. '"'
        end
    end
    local text = {}
    for index = first, last do
        table.insert(text, (index > first and out[index].space or '') .. out[index].text)
    end
    table.insert(items, { text = table.concat(text), star = star })
end

-- The table, in lower case, of which a * at tokens[index] selects all columns, when its FROM names only that one, like
-- files of SELECT * FROM main.files AS f WHERE; else nil, like for a join
function starred(tokens, index)
    local function at(i)
        return tokens[i] or tokens[#tokens]
    end
    local depth = 0
    for i = index + 1, #tokens do
        local text = string.upper(tokens[i].text)
        if text == '(' then
            depth = depth + 1
        elseif text == ')' or tokens[i].kind == 'end' then
            if depth == 0 then
                return nil
            end
            depth = depth - 1
        elseif depth == 0 and text == 'FROM' and tokens[i].kind == 'word' then
            -- After the schema, like main., and before the alias of the table, like AS f
            local name = (at(i + 2).text == '.' and i + 3 or i + 1)
            local after = at(name + 1)
            if string.upper(after.text) == 'AS' then
                after = at(name + 3)
            elseif after.kind == 'word' and not ENDS[string.upper(after.text)] then
                after = at(name + 2)
            end
            local token = at(name)
            local named = (token.kind == 'word' and token.text) or
                    ((token.kind == 'quoted' or token.kind == 'string') and string.sub(token.text, 2, -2)) or nil
            if named == nil or not (after.kind == 'end' or after.text == ')' or ENDS[string.upper(after.text)]) then
                return nil
            end
            return string.lower(named)
        end
    end
    return nil
end

-- The SQL of a projection, selection or sort order with the location columns NULL, like on Android 10, so the rows,
-- their order and the values agree: a column also with its table (images.latitude), in any case and quoted, also as
-- a string next to a dot (images.'latitude'), but not an alias or a type of that name (latitude AS la, _id latitude,
-- CAST(x AS latitude)). Each becomes NULLIF of itself, which SQLite still resolves, so a column which is not there
-- fails like in the SQL of the app (no such column: bogus.latitude). With items (a projection), adds each of its
-- columns, { text, star }, star for * of all columns of the table, which before puts in its place. A * in a SELECT of
-- the app gets the columns of its table from tables (by name, like files), the location columns NULL. With inner,
-- for a table without location columns, like audio, only in the SELECTs the app writes, like subqueries, as SQLite
-- refuses the others (no such column). Aliases are those of the projection which a bare name of a sort order sorts
-- by, like latitude of _id AS latitude (resolveOrderGroupBy of SQLite); a projection adds its own. Returns also
-- whether a FROM at the top selects from another table, like latitude FROM files UNION SELECT _id of a projection.
-- shortcut: a * of a join or a subquery, or with its table in front (f.*), in a SELECT of the app selects NULL, as
-- does a * of files, images or video when the provider gives no columns of it; a NATURAL JOIN still compares the real
-- latitude and USING (latitude) does not compile; and a sort order of an alias in parentheses, like (latitude) DESC,
-- gets NULL; upgrade if apps query them like that
function rewrite(sql, items, inner, tables, aliases)
    local tokens = tokenize(sql)
    local out, depth, first, star = {}, 0, 1, false
    -- Per depth of parentheses, whether its names are of a SELECT of the app, else of the table of the uri, and
    -- whether they are in the list of columns of a SELECT, up to its FROM
    local selects, lists = {}, {}
    -- After a FROM at the top, the commas of a projection no longer separate its columns
    local from = false
    for index, token in ipairs(tokens) do
        local following = tokens[index + 1] or token
        -- SQLite takes a string next to a dot as a name (nm ::= STRING in nm DOT nm of parse.y)
        local quoted = (token.kind == 'quoted' or (token.kind == 'string' and
                ((#out > 0 and out[#out].text == '.') or following.text == '.')))
        local name = (token.kind == 'word' and token.text) or (quoted and string.sub(token.text, 2, -2)) or nil
        local active = (not inner or selects[depth] == true)
        local column = (active and name ~= nil and located(name) or nil)
        -- The table in front, like images. of images.latitude
        local chain = #out + 1
        if column ~= nil or token.text == '*' then
            while chain > 2 and out[chain - 1].text == '.' and (out[chain - 2].kind == 'word' or
                    out[chain - 2].kind == 'quoted' or out[chain - 2].kind == 'string') do
                chain = chain - 2
            end
        end
        local before = out[chain - 1]
        local word = (before ~= nil and before.kind == 'word' and string.upper(before.text) or nil)
        local ahead = string.upper(following.text)
        local hide, text, expanded = false, 'NULL', false
        if column ~= nil then
            local renamed = alias(before, token)
            if renamed and items ~= nil and aliases ~= nil and depth == 0 and not from then
                aliases[column] = true
            end
            local sorted = (items == nil and aliases ~= nil and aliases[column] and depth == 0 and chain > #out and
                    (before == nil or before.text == ',') and
                    (following.kind == 'end' or ahead == ',' or ahead == 'ASC' or ahead == 'DESC' or ahead == 'COLLATE'))
            -- Not a function or a table of that name either
            hide = (ahead ~= '(' and ahead ~= '.' and not renamed and not sorted)
            local ref = {}
            for at = chain, #out do
                table.insert(ref, out[at].text)
            end
            table.insert(ref, token.text)
            ref = table.concat(ref)
            text = 'NULLIF(' .. ref .. ', ' .. ref .. ')'
            -- A column of the list of a SELECT keeps its name, like for x.latitude of (SELECT latitude FROM files) AS x
            if lists[depth] and (word == 'SELECT' or word == 'DISTINCT' or word == 'ALL' or
                    (before ~= nil and before.text == ',')) and
                    (following.kind == 'end' or ahead == ',' or ahead == ')' or ahead == 'FROM') then
                text = text .. ' AS ' .. column
            end
        elseif active and token.text == '*' and (chain <= #out or before == nil or before.text == ',' or
                word == 'SELECT' or word == 'DISTINCT' or word == 'ALL') then
            if items ~= nil and depth == 0 and chain == first and (following.kind == 'end' or following.text == ',') then
                star = true
            else
                -- Only files, images and video have the location columns, so a * of another table, like audio, stays
                local named = (chain > #out and starred(tokens, index) or nil)
                hide = (named == nil or TABLES[named] ~= nil)
                local columns = (hide and named ~= nil and tables ~= nil and tables(named) or nil)
                if columns ~= nil then
                    text, expanded = table.concat(columns, ', '), true
                end
            end
        end
        if hide then
            local head = out[chain] or token
            for drop = #out, chain, -1 do
                out[drop] = nil
            end
            table.insert(out, { kind = 'word', text = text, space = head.space, first = head.first,
                                last = token.last, column = column, hidden = true, expanded = expanded })
        else
            if items ~= nil and (token.kind == 'end' or (token.text == ',' and depth == 0 and not from)) then
                finish(sql, out, first, star, items)
                -- A comment at the end stays, as it hides the rest of the SQL of the provider, like -- of * FROM files --
                if token.kind == 'end' and string.find(token.space, '--', 1, true) then
                    items[#items].text = items[#items].text .. token.space
                end
                first, star = #out + 2, false
            end
            if token.text == '(' then
                depth = depth + 1
                selects[depth], lists[depth] = selects[depth - 1], nil
            elseif token.text == ')' then
                selects[depth], lists[depth] = nil, nil
                depth = depth - 1
            elseif token.kind == 'word' and string.upper(token.text) == 'SELECT' then
                selects[depth], lists[depth] = true, true
            elseif token.kind == 'word' and string.upper(token.text) == 'FROM' then
                lists[depth] = nil
                from = from or depth == 0
            end
            table.insert(out, token)
        end
    end
    local text = {}
    for _, token in ipairs(out) do
        table.insert(text, token.space .. token.text)
    end
    return table.concat(text), from
end

-- A Java array of n strings of a list, where nil is null
function stringarray(list, n)
    local array = luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.String'), n)
    for index = 1, n do
        if list[index] ~= nil then
            array[index] = list[index]
        end
    end
    return array
end

-- The columns of the table of a query of the media provider, which it gives for a selection of no rows, as the
-- projection: the location columns NULL; nil when the provider gives none
function columnsof(param, uri)
    local this = param:getThis()
    local args = luajava.newInstance('android.os.Bundle')
    args:putString('android:query-arg-sql-selection', '0')
    local names = param:unhooked(function()
        local ok, names = pcall(function()
            local cursor = this:query(uri, nil, args, nil)
            if cursor == nil then
                return nil
            end
            local names = cursor:getColumnNames()
            cursor:close()
            return names
        end)
        return (ok and names or nil)
    end)
    if names == nil then
        return nil
    end
    local columns = {}
    for index = 1, names['length'] do
        local name = names[index]
        local quoted = (string.find(name, '^[%a_][%w_]*$') and name or '"' .. string.gsub(name, '"', '""') .. '"')
        columns[index] = (located(name) ~= nil and 'NULL AS ' .. quoted or quoted)
    end
    return columns
end

-- Whether the table of a uri of the media provider has the location columns, as its UriMatcher finds it, with # for
-- digits: */images/media, */video/media, */file and */object (MTP), each also with /# of an item
function geotagged(uri)
    local segments = uri:getPathSegments()
    local path = {}
    for index = 1, segments:size() - 1 do
        path[index] = segments:get(index)
    end
    if #path > 0 and string.find(path[#path], '^%d+$') then
        path[#path] = nil
    end
    local name = table.concat(path, '/')
    return name == 'images/media' or name == 'video/media' or name == 'file' or name == 'object'
end

-- Whether the media provider answers a query on /external/ without running it, with a cursor of no rows named like
-- the projection, as the app op of READ_EXTERNAL_STORAGE is not allowed, like for an app targeting Android 5.1 or
-- older whose storage the user revoked, and the app has no grant of the uri (ContentProvider.Transport.query and
-- enforceReadPermissionInner): there is nothing to hide, and NULL AS latitude would name a column like that
function ignored(param, uri)
    local path = uri:getPath()
    if path == nil or string.sub(path, 1, 10) ~= '/external/' then
        return false
    end
    local context = param:getApplicationContext()
    local process = luajava.bindClass('android.os.Process')
    return param:unhooked(function()
        return context:getSystemService('appops'):checkOpNoThrow('android:read_external_storage', process:myUid(),
                context:getPackageName()) ~= MODE_ALLOWED and
                context:checkUriPermission(uri, process:myPid(), process:myUid(), FLAG_GRANT_READ_URI_PERMISSION) ~=
                        PERMISSION_GRANTED
    end)
end

-- A query of the media provider (the query of API 26, which the others call): the location columns NULL in the
-- projection, the selection and the sort order, the latter two in a copy of the Bundle of the app
function before(hook, param)
    if hook:getMethodName() ~= 'query' or not media(param:getArgument(0)) then
        return false
    end
    local uri = param:getArgument(0)
    rewritten, asked = false, nil
    if ignored(param, uri) then
        rewritten = true
        return false
    end
    local inner = not geotagged(uri)
    local hidden = true
    -- The columns of files, images and video on the volume of the uri, for a * of a SELECT of the app, asked once
    local known = {}
    local function tables(name)
        local segments = uri:getPathSegments()
        if TABLES[name] ~= nil and known[name] == nil and segments:size() > 0 then
            local text = 'content://' .. uri:getAuthority() .. '/' .. segments:get(0) .. '/' .. TABLES[name]
            known[name] = columnsof(param, luajava.bindClass('android.net.Uri'):parse(text)) or false
        end
        return known[name] or nil
    end
    -- The aliases of the projection, by which ORDER BY sorts a bare name, where WHERE takes the column
    local aliases = {}

    -- No projection, or none, is * of all columns of the table, which SQLiteQueryBuilder selects then
    local projection = param:getArgument(1)
    -- A projection with a FROM of its own selects from another table, which can have the location columns
    for index = 1, (inner and projection ~= nil and projection['length'] or 0) do
        if projection[index] ~= nil and select(2, rewrite(projection[index], nil, true)) then
            inner = false
        end
    end
    local items, changed, star = {}, false, false
    if projection == nil or projection['length'] == 0 then
        items[1] = { star = not inner }
    else
        for index = 1, projection['length'] do
            local text = projection[index]
            if text == nil then
                table.insert(items, {})
            elseif rewrite(text, items, inner, tables, aliases) ~= text then
                changed = true
            end
        end
    end
    for _, item in ipairs(items) do
        star = star or item.star
    end
    if star then
        local columns = columnsof(param, uri)
        hidden = (columns ~= nil)
        for _, column in ipairs(columns or {}) do
            changed = changed or string.find(column, '^NULL AS ') ~= nil
        end
        if changed then
            for _, item in ipairs(items) do
                item.columns = (item.star and columns or nil)
            end
        end
    end
    if changed then
        local list, n = {}, 0
        for _, item in ipairs(items) do
            for _, text in ipairs(item.columns or { item.text or NULL }) do
                n = n + 1
                list[n] = (text ~= NULL and text or nil)
            end
        end
        param:setArgument(1, stringarray(list, n))
    end

    -- A copy: the app could read its own Bundle back
    local bundle = param:getArgument(2)
    local fake = false
    if bundle ~= nil then
        local copy = luajava.newInstance('android.os.Bundle', bundle)
        for _, key in ipairs({ 'android:query-arg-sql-selection', 'android:query-arg-sql-sort-order' }) do
            local text = copy:getString(key)
            local sorting = (key == 'android:query-arg-sql-sort-order' and aliases or nil)
            local sql = (text ~= nil and rewrite(text, nil, inner, tables, sorting) or nil)
            if sql ~= text then
                copy:putString(key, sql)
                fake = true
            end
        end
        -- Without the sort order the provider sorts by these, joined (ContentResolver.createSqlSortClause)
        local sort = copy:getStringArray('android:query-arg-sort-columns')
        if sort ~= nil then
            local list, differ = {}, false
            for index = 1, sort['length'] do
                list[index] = (sort[index] ~= nil and rewrite(sort[index], nil, inner, tables, aliases) or nil)
                differ = differ or list[index] ~= sort[index]
            end
            if differ then
                copy:putStringArray('android:query-arg-sort-columns', stringarray(list, sort['length']))
                fake = true
            end
        end
        if fake then
            param:setArgument(2, copy)
        end
    end
    if changed or fake then
        asked = { projection = projection, bundle = bundle }
    end
    rewritten = hidden
    return false
end

-- The cursor, or nil, of the query with the arguments of the app, unhooked, or the exception it throws, which
-- CompletableFuture.handle gets as the Throwable itself, where pcall gets only its text
function original(param, arguments)
    local this, uri, signal = param:getThis(), param:getArgument(0), param:getArgument(3)
    local cursor, thrown
    local run = luajava.createProxy('java.util.function.Function', { apply = function()
        return this:query(uri, arguments.projection, arguments.bundle, signal)
    end })
    -- The exception comes wrapped in a CompletionException
    local keep = luajava.createProxy('java.util.function.BiFunction', { apply = function(value, ex)
        cursor, thrown = value, (ex ~= nil and ex:getCause() or nil)
    end })
    param:unhooked(function()
        luajava.bindClass('java.util.concurrent.CompletableFuture'):completedFuture(nil):thenApply(run):handle(keep)
    end)
    return cursor, thrown
end

-- A query of the media provider with the location columns which before could not hide: the cursor of the provider
-- behind a proxy, which gives the location columns like for a photo without a location (null) and the rest, like
-- the notification URI, the types and the moving, as it is. When SQLite refused the SQL before changed, its message
-- shows that SQL (", while compiling: "), so the app gets the error of its own arguments.
-- shortcut: a query whose own SQL works but whose changed SQL does not, like a * of a join or of a subquery NULL with
-- a column of it named, keeps the error of the changed SQL, as the cursor of its own SQL would give the location;
-- upgrade if apps query like that
function query(param)
    local uri = param:getArgument(0)
    local result = param:getResult()
    local done, arguments = rewritten, asked
    rewritten, asked = false, nil
    local ex = param:getException()
    if ex ~= nil then
        if arguments == nil or not param:isInstance(ex, 'android.database.sqlite.SQLiteException') then
            return false
        end
        local cursor, thrown = original(param, arguments)
        -- Not an error of this script, like a LuaError; with the stack trace of the call of the app
        if thrown ~= nil and param:isInstance(thrown, 'android.database.sqlite.SQLiteException') then
            thrown:setStackTrace(ex:getStackTrace())
            param:setResult(thrown)
        elseif cursor ~= nil then
            param:unhooked(function()
                cursor:close()
            end)
        end
        return false
    end
    if done or result == nil or not media(uri) then
        return false
    end
    local hidden = {}
    local any = false
    for _, name in ipairs(COLUMNS) do
        local index = result:getColumnIndex(name)
        if index >= 0 then
            hidden[index] = true
            any = true
        end
    end
    if not any then
        return false
    end
    -- The functions of the proxy get the arguments of the method only; the others call the method of the cursor.
    -- shortcut: a long passes Lua as a double, exact up to 2^53, like dates and ids of media; upgrade for larger ones
    local methods = {}
    for method, value in pairs({ getString = NULL, getBlob = NULL, getDouble = 0, getFloat = 0, getLong = 0,
                                 getInt = 0, getShort = 0, getType = 0, isNull = true }) do
        methods[method] = function(index, ...)
            if hidden[index] then
                return (value ~= NULL and value or nil)
            end
            return result[method](result, index, ...)
        end
    end
    -- CharArrayBuffer of a hidden column: empty, like for null
    methods.copyStringToBuffer = function(index, buffer)
        if hidden[index] then
            buffer.sizeCopied = 0
            return
        end
        result:copyStringToBuffer(index, buffer)
    end
    setmetatable(methods, { __index = function(_, method)
        -- The proxy itself for equals and hashCode, like any object
        if method == 'equals' or method == 'hashCode' then
            return nil
        end
        return function(...)
            return result[method](result, ...)
        end
    end })
    param:setResult(luajava.createProxy('android.database.Cursor', methods))
    return true
end

-- Whether a tag of EXIF is of the location, like GPSLatitude, GPSAltitude and GPSDateStamp
function gps(tag)
    return tag ~= nil and string.sub(tag, 1, 3) == 'GPS'
end

-- Whether the app set the tags on this object itself (see setAttribute), like a photo it writes, which stay
function own(param, ...)
    local this = param:getThis()
    for _, tag in ipairs({ ... }) do
        if this == nil or param:getValue('exif.' .. tag, this) == nil then
            return false
        end
    end
    return true
end

-- Whether the value of a tag is zeroed already, like the media provider zeroes the bytes of the GPS tags for apps
-- without ACCESS_MEDIA_LOCATION, which ExifInterface then gives as 0/0 or empty: such tags stay like Android gives them
function zeroed(param, tag, value)
    if value == nil then
        local this = param:getThis()
        if this == nil then
            return false
        end
        value = param:unhooked(function()
            return this:getAttribute(tag)
        end)
    end
    return type(value) == 'string' and string.find(value, '^[0/,%s%z]*$') ~= nil
end

-- The XMP with the values of its GPS properties blanked by spaces, like the media provider does, so its length and
-- offsets stay: elements, like <exif:GPSLatitude>52,30.12N</exif:GPSLatitude>, and attributes, like
-- exif:GPSLatitude="52,30.12N"
-- shortcut: the exif prefix only, which Adobe and Android write; upgrade for other prefixes of its namespace
function blankxmp(text)
    local function spaces(value)
        -- A space for each character, not each byte of the UTF-8 of Lua
        return string.rep(' ', #(string.gsub(value, '[\128-\191]', '')))
    end
    text = string.gsub(text, '(<exif:GPS(%w+)[^>]*>)(.-)(</exif:GPS%2>)', function(open, _, value, close)
        return open .. spaces(value) .. close
    end)
    text = string.gsub(text, '(exif:GPS%w+%s*=%s*)(["\'])(.-)%2', function(name, quote, value)
        return name .. quote .. spaces(value) .. quote
    end)
    return text
end

-- The XMP of getAttribute (text) or getAttributeBytes (bytes, of which each is a character of ISO 8859-1)
function xmp(param, result)
    if type(result) == 'string' then
        local fake = blankxmp(result)
        return (fake ~= result and fake or nil)
    end
    local latin = luajava.bindClass('java.nio.charset.StandardCharsets').ISO_8859_1
    local text = luajava.newInstance('java.lang.String', result, latin)
    local fake = blankxmp(text)
    if fake == text then
        return nil
    end
    local buffer = latin:encode(fake)
    return luajava.bindClass('java.util.Arrays'):copyOf(buffer:array(), buffer:limit())
end

function after(hook, param)
    local method = hook:getMethodName()
    if method == 'query' then
        return query(param)
    end
    if param:getException() ~= nil then
        return false
    end
    local result = param:getResult()
    local fake
    if method == 'setAttribute' then
        -- A tag the app set, which reads back on this object; null removes it
        local tag = param:getArgument(0)
        if tag ~= nil and param:getThis() ~= nil then
            param:putValue('exif.' .. tag, param:getArgument(1) ~= nil and true or nil, param:getThis())
        end
        return false
    elseif method == 'extractMetadata' then
        if param:getArgument(0) ~= METADATA_KEY_LOCATION or result == nil then
            return false
        end
        param:setResult(nil)
        return true, result, nil
    elseif method == 'getLatLong' then
        -- getLatLong(float[]) fills the array and returns whether there is a location; getLatLong() of AndroidX
        -- returns the array of doubles or null
        if result == nil or result == false or own(param, 'GPSLatitude', 'GPSLongitude') then
            return false
        end
        if hook:getReturnType() == 'boolean' then
            param:setResult(false)
        else
            param:setResult(nil)
        end
        return true
    elseif method == 'getGpsDateTime' then
        -- -1 of Android, null of AndroidX, when there is no GPS time
        if result == nil or result == -1 or own(param, 'GPSDateStamp') then
            return false
        end
        if hook:getReturnType() == 'long' then
            param:setResult(-1)
        else
            param:setResult(nil)
        end
        return true
    elseif method == 'getAltitude' then
        local default = param:getArgument(0)
        if result == default or own(param, 'GPSAltitude') then
            return false
        end
        param:setResult(default)
        return true, tostring(result), tostring(default)
    elseif method == 'hasAttribute' then
        -- hasAttribute of a GPS tag is false, like a tag which is not there
        local tag = param:getArgument(0)
        if not gps(tag) or result == false or own(param, tag) or zeroed(param, tag) then
            return false
        end
        param:setResult(false)
        return true, tag, nil
    else
        -- getAttribute, getAttributeBytes, getAttributeInt, getAttributeDouble and getAttributeRange of a GPS tag:
        -- like a tag which is not there, null or the default
        local tag = param:getArgument(0)
        if tag == 'Xmp' and result ~= nil and (method == 'getAttribute' or method == 'getAttributeBytes') and
                not own(param, tag) then
            fake = xmp(param, result)
            if fake == nil then
                return false
            end
            param:setResult(fake)
            return true, tag, nil
        end
        if not gps(tag) or own(param, tag) or zeroed(param, tag, method == 'getAttribute' and result or nil) then
            return false
        end
        if method == 'getAttributeInt' or method == 'getAttributeDouble' then
            fake = param:getArgument(1)
        end
        if result == fake then
            return false
        end
        param:setResult(fake)
        return true, tag, nil
    end
end
