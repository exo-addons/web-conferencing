/**
 * Web Conferencing integration for eXo Platform.
 */
(function($, cCometD) {
	"use strict";
	
	// ******** Utils ********

	var getRandomArbitrary = function(min, max) {
	  return Math.floor((Math.random() * (max - min - 1) + min) + 1);
	};

	/**
	 * Universal client ID for use in logging, services connectivity and related cases.
	 */
	var clientId = "" + getRandomArbitrary(100000, 999998);
	
	var errorText = function(err) {
		return err && err.message ? err.message : "";
	};
	
	var tryParseJson = function(message) {
		var src = message.data ? message.data : (message.error ? message.error : message.failure); 
		if (src) {
			try {
				if (typeof src === "string" && (src.startsWith("{") || src.startsWith("["))) {
					return JSON.parse(src);					
				}
			} catch(e) {
				log.debug("Error parsing '" + src + "' as JSON: " + e, e);
			}				
		}
		return src;
	};
	
	var cometdError = function(response) {
		var msg = "[" + response.id + "] ";
		if (response.channel) {
			msg += response.channel + " ";
		} else if (response.failure && response.failure.message && response.failure.message.channel) {
			msg += response.failure.message.channel + " ";
		}
		if (response.error) {
			msg += response.error + " ";
		}
		if (response.failure) {
			msg += response.failure.reason + " ";
		}
		if (response.data) {
			msg += response.data;
		}
		return msg;
	};
	
	var cometdInfo = function(response) {
		return "[" + response.id + "] " + response.channel;
	};
	
	// CometD transport bus
	var cometd, cometdContext;
	
	var cometdParams = function(params) {
		return $.extend(params, cCometD.eXoSecret, cometdContext);
	};
	
	/**
	 * Spools buffered logs to CometD channel.
	 */
	function LogSpooler() {
		var buff = [];
		
		var flush = function() {
			if (buff.length > 0) {
				if (cometd) {
					var bucket = buff; 
					buff = [];
					// spool in CometD batch
					cometd.batch(function() {
						for (var i=0; i<bucket.length; i++) {
							var msg = bucket[i];
							//log.trace("Sending remote log: " + JSON.stringify(msg));
							cometd.remoteCall("/webconferencing/logs", msg, function(response) {
								var result = tryParseJson(response);
								if (!response.successful) {
									log.trace("ERROR: Failed to send log message to remote spooler", cometdError(response));
								}
							});
						}
					});
				} else if (buff.length > 100) {
					log.trace("WARNING: CometD not available. Log cannot be spooled remotely and will be cut to avoid memory leak");
					buff = buff.slice(20);
				}
			}
		};
		
		var spoolerJob;
		var activate = function() {
			if (buff.length > 5) {
				flush();
			}
			if (!spoolerJob) {
				spoolerJob = setInterval(function() { 
					flush();
				}, 5000);
				setTimeout(function() { 
					clearInterval(spoolerJob);
					spoolerJob = null;
					flush();
				}, 180000);				
			}
		};
		
		var windowListener = function(e) {
			flush();
		};
		
		// We attempt to save all logs on page close
		$(window).bind("beforeunload", windowListener);
		$(window).bind("unload", windowListener);
		
		this.add = function(msg) {
			buff.push(msg);
			activate();
		};
	}
	
	/**
	 * Singleton spooler used by all loggers.
	 */
	var logSpooler = new LogSpooler();
	
	/**
	 * Logging to browser console and optionally (if enabled) spool the log to remote server.
	 */
	function Logger() {
		
		var providerType = null;
		var prefix = null;
		var remote = false;
		
		// Private methods
		var setPrefix = function(newVal) {
			prefix = newVal ? newVal : null;
		};
		
		var logRemote = function(level, message, date) {
			if (remote) {
				var data;
				if (typeof message === "function") {
					data = message();
					if (typeof data === "string") {
						data = {
							message : msg
						};
					}
				} else if (typeof message === "string") {
					data = {
						message : message
					};
				} else {
					data = message;
				}
				var msg = cometdParams({
					data : data,
					level : level,
					prefix : prefix,
					provider : providerType,
					timestamp : date
				});
				logSpooler.add(msg);
			} // else, remote spooler not set			
		};
		
		var toLog = function(level, msg, err, localOnly) {
			// Log to browser console and remotely when remote service become available.
			var msgLine = msg;
			if (err) {
				msgLine += ". Error: ";
				if (err.name || err.message) {
					if (err.name) {
						msgLine += "[" + err.name + "] ";
					}
					if (err.message) {
						msgLine += err.message;
					}
				} else {
					msgLine += (typeof err === "string" ? err : JSON.stringify(err) 
								+ (err.toString && typeof err.toString === "function" ? "; " + err.toString() : ""));
				}
			}
			var msgDate = new Date().toISOString();
			if (typeof console !== "undefined" && typeof console.log === "function") {
				var levelPad = (level.toUpperCase() + "     ").slice(0, 5);
				var localPrefix = "[";
				if (providerType) {
					localPrefix += providerType; 
				}
				if (prefix) {
					if (localPrefix.length > 1) {
						localPrefix += ".";
					}
					localPrefix += prefix; 
				}
				if (localPrefix.length == 1) {
					localPrefix += "???";
				}
				localPrefix += "_" + clientId + "]";
				console.log("| " + levelPad + " | " + localPrefix + " " + msgLine + " -- " + msgDate);
				if (err && err.stack) {
					console.log(err.stack);
				}
			}
			if (!localOnly) {
				logRemote(level, msgLine, msgDate);
			}
		};
		
		// It's a logger that will be returned to user code (public methods).
		function Client() {
			/**
			 * Sets text to use as a prefix (e.g. provider prefix).
			 */
			this.prefix = function(thePrefix) {
				setPrefix(thePrefix);
				return this;
			};
			
			/**
			 * Add info level message to user log.
			 */
			this.info = function(message, err) {
				toLog("info", message, err);
				return this;
			};
			
			/**
			 * Add warn level message to user log.
			 */
			this.warn = function(message, err) {
				toLog("warn", message, err);
				return this;
			};
			
			/**
			 * Add error level message to user log.
			 */
			this.error = function(message, err) {
				toLog("error", message, err);
				return this;
			};
			
			/**
			 * Add debug level message to user log.
			 */
			this.debug = function(message, err) {
				toLog("debug", message, err);
				return this;
			};
			
			/**
			 * Add trace level message to user log.
			 */
			this.trace = function(message, err) {
				toLog("trace", message, err, true); // traces go to browser console only
				return this;
			};
		}
		
		var log;
		this.get = function() {
			if (!log) {
				log = new Client();
			}
			return log;
		};
		
		this.remoteLog = function(value) {
			remote = value;
			return this;
		};
		
		this.prefix = function(thePrefix) {
			// FYI such method also exists on Client (as setSpoller()), but it doesn't returns a 'this' object for chaining
			setPrefix(thePrefix);
			return this;
		};
		
		this.provider = function(theProvider) {
			// provider type will be used as a prefix or precede a user prefix if an one set.
			providerType = theProvider ? theProvider : null;
			return this;
		};
	}
	
	// core log not enabled for remote spooling until some provider will do this, see init()
	var log = new Logger().prefix("webconferencing").get();
	//log.trace("> Loading at " + location.origin + location.pathname);
	
	/** 
	 * Polyfill ECMAScript 2015's String.startsWith().
	 * */
	if (!String.prototype.startsWith) {
    String.prototype.startsWith = function(searchString, position) {
      position = position || 0;
      return this.substr(position, searchString.length) === searchString;
	  };
	}
	
	var pageBaseUrl = function(theLocation) {
		if (!theLocation) {
			theLocation = window.location;
		}

		var theHostName = theLocation.hostname;
		var theQueryString = theLocation.search;

		if (theLocation.port) {
			theHostName += ":" + theLocation.port;
		}

		return theLocation.protocol + "//" + theHostName;
	};

	var getPortalUser = function() {
		return eXo.env.portal.userName;
	};

	var decodeString = function(str) {
		if (str) {
			try {
				str = str.replace(/\+/g, " ");
				str = decodeURIComponent(str);
				return str;
			} catch(e) {
				log.warn("Error decoding string " + str + ". " + e, e);
			}
		}
		return null;
	}

	var encodeString = function(str) {
		if (str) {
			try {
				str = encodeURIComponent(str);
				return str;
			} catch(e) {
				log.warn("Error decoding string " + str + ". " + e, e);
			}
		}
		return null;
	};

	// ******** UI utils **********

	var messages; // will be initialized by WebConferencing.init()

	var message = function(key) {
		return messages ? messages["webconferencing." + key] : "";
	};
	
	/**
	 * Open pop-up.
	 */
	var popupWindow = function(url) {
		var w = 650;
		var h = 400;
		var left = (screen.width / 2) - (w / 2);
		var top = (screen.height / 2) - (h / 2);
		return window.open(url, 'contacts', 'width=' + w + ',height=' + h + ',top=' + top + ',left=' + left);
	};
	
	// UI messages
	// Used to show immediate notifications in top right corner.
	// This functionality requires pnotifyJQuery and jqueryui CSS.

	var NOTICE_WIDTH = "380px";
  
  var isIOS = /iPhone|iPod|iPad/.test(navigator.userAgent);
  var isAndroid = /Android/.test(navigator.userAgent);
  var isWindowsMobile = /IEmobile|WPDesktop|Windows Phone/i.test(navigator.userAgent) || /WM\s*\d.*.Edge\/\d./i.test(navigator.userAgent);
  
  var CACHE_LIVETIME = 30000;
  
	/**
	 * Show notice to user. Options support "icon" class, "hide", "closer" and "nonblock" features.
	 */
	var notice = function(type, title, text, options) {
		var noticeOptions = {
			title : title,
			text : text,
			type : type,
			icon : "picon " + ( options ? options.icon : ""),
			hide : options && typeof options.hide != "undefined" ? options.hide : false,
			delay : options && typeof options.delay != "undefined" ? options.delay : undefined,
			closer : options && typeof options.closer != "undefined" ? options.closer : true,
			sticker : false,
			opacity : .85,
			shadow : true,
			width : options && options.width ? options.width : NOTICE_WIDTH,
			nonblock : options && typeof options.nonblock != "undefined" ? options.nonblock : false,
			nonblock_opacity : .45,
			addclass : options && options.addclass ? options.addclass : "",
			cornerclass : options && options.cornerclass ? options.cornerclass : "",
			after_init : function(pnotify) {
				if (options && typeof options.onInit == "function") {
					options.onInit(pnotify);
				}
			}
		};
		return $.pnotify(noticeOptions);
	};

	/**
	 * Show error notice to user. Error will stick until an user close it.
	 */
	var noticeError = function(title, text, onInit) {
		return notice("error", title, text, {
			icon : "picon-dialog-error",
			hide : false,
			delay : 0,
			onInit : onInit
		});
	};

	/**
	 * Show info notice to user. Info will be shown for 8sec and hidden then.
	 */
	var noticeInfo = function(title, text, onInit) {
		return notice("info", title, text, {
			hide : true,
			delay : 8000,
			icon : "picon-dialog-information",
			onInit : onInit
		});
	};

	/**
	 * Show warning notice to user. Info will be shown for 8sec and hidden then.
	 */
	var noticeWarn = function(title, text, onInit) {
		return notice("exclamation", title, text, {
			hide : true,
			delay : 30000,
			icon : "picon-dialog-warning",
			onInit : onInit
		});
	};
	
	/**
	 * Show warning notice bar to user. Info will be shown for 8sec and hidden then.
	 */
	var noticeWarnBar = function(title, text, onInit) {
		return notice("exclamation", title, text, {
			hide : false,
			delay : 30000,
			icon : "picon-dialog-warning",
			width : "100%",
			addclass : "stack-bar-top",
      cornerclass : "",
			onInit : onInit
		});
	};
	
	var htmlRegx = /<[a-z][\s\S]*>/i;
	var appendContent = function($target, content) {
		if (typeof content === "object" || typeof content === "function") {
			$target.append(content); // assuming supported by jQuery object or function 
		} else if (typeof content === "string") {
			if (htmlRegx.test(content)) {
				$target.html(content);
			} else {
				$target.text(content);
			}
		} else if (content) {
			$target.text(content);
		} // else nothing can append
		return $target.children();
	};
	
	var dialog = function(title, messageText, type) {
		var loader = $.Deferred();
		var $dialog = $("#webconferencing-dialog");
		if ($dialog.length == 0) {
			$dialog = $("<div class='uiPopupWrapper' id='webconferencing-dialog' style='display: none;'><div>");
			$(document.body).append($dialog);
			$dialog.load("/webconferencing/ui/dialog.html", function(content, textStatus) {
				if (textStatus == "success" || textStatus == "notmodified") {
					loader.resolve($dialog);
				} else {
					loader.reject(content);
				}
			});
		} else {
			loader.resolve($dialog);
		}
		var process = $.Deferred();
		loader.done(function($dialog) {
			process.progress($dialog);
			if (title) {
				appendContent($dialog.find(".popupTitle"), title);
			}
			if (messageText) {
				appendContent($dialog.find(".contentMessage"), messageText);
			}
			var $actions = $dialog.find(".popupActions");
			var $okButton = $actions.find(".okButton");
			var $cancelButton = $actions.find(".cancelButton");
			var $icon = $dialog.find(".popupIcon");
			if (typeof type === "string") {
				// Clean previous classes
				$icon.find("i").attr("class", "").addClass("uiIcon" + type.charAt(0).toUpperCase() + type.slice(1));
				if (type.indexOf("Error") > 0 || type.indexOf("Warn") > 0 || type.indexOf("Info") > 0) {
					$cancelButton.hide();
				}
			} else {
				// otherwise don't show any icon
				$icon.hide();
			}
			$okButton.text(message("ok"));
			$okButton.click(function() {
				process.resolve("ok");
			});
			$cancelButton.text(message("cancel"));
			$cancelButton.click(function() {
				process.resolve("cancel");
			});
			process.always(function() {
				$dialog.hide();
			});
			//
			$dialog.show();
		}).fail(function(err) {
			process.reject(err);
		});
		return process.promise();
	};
	
	var showError = function(title, text) {
		return dialog(title, text, "ColorError");
	};
	
	var showWarn = function(title, text) {
		return dialog(title, text, "ColorWarning");
	};
	
	var showInfo = function(title, text) {
		return dialog(title, text, "Information");
	};
	
	var showConfirm = function(title, text) {
		return dialog(title, text, "Question");
	};

	// ******** REST services ********
	var prefixUrl = pageBaseUrl(location);

	var initRequest = function(request) {
		var process = $.Deferred();

		// stuff in textStatus is less interesting: it can be "timeout",
		// "error", "abort", and "parsererror",
		// "success" or smth like that
		request.fail(function(jqXHR, textStatus, err) {
			if (jqXHR.status != 309) {
				// check if response isn't JSON
				var data;
				try {
					data = $.parseJSON(jqXHR.responseText);
					if ( typeof data == "string") {
						// not JSON
						data = jqXHR.responseText;
					}
				} catch(e) {
					// not JSON
					data = jqXHR.responseText;
				}
				// in err - textual portion of the HTTP status, such as "Not
				// Found" or "Internal Server Error."
				process.reject(data, jqXHR.status, err, jqXHR);
			}
		});
		// hacking jQuery for statusCode handling
		var jQueryStatusCode = request.statusCode;
		request.statusCode = function(map) {
			var user502 = map[502];
			if (!user502) {
				map[502] = function() {
					// treat 502 as request error also
					process.reject("Bad gateway", 502, "error");
				};
			}
			return jQueryStatusCode(map);
		};

		request.done(function(data, textStatus, jqXHR) {
			process.resolve(data, jqXHR.status, textStatus, jqXHR);
		});

		// custom Promise target to provide an access to jqXHR object
		var processTarget = {
			request : request
		};
		return process.promise(processTarget);
	};

	function Cache() {
		var cache = {};
		var locks = {};
		
		this.put = function(key, value) {
			cache[key] = value;
			setTimeout(function() {
				cache[key] = null;
  		}, CACHE_LIVETIME);
		};
		
		this.get = function(key) {
			// TODO do we need this check?
			if (cache.hasOwnProperty(key)) {
				return cache[key];
			} else {
				return null;
			}
		};
		
		this.remove = function(key) {
			cache[key] = null;
		};
		
		this.lock = function(key, worker) {
			locks[key] = worker;
		};
		
		this.locked = function(key) {
			if (locks.hasOwnProperty(key)) {
				return locks[key];
			} else {
				return null;
			}
		};
		
		this.unlock = function(key) {
			locks[key] = null;
		};
	}
	
	var getCached = function(key, cache, getFunc) {
		var res = cache.locked(key);
		if (res) {
			return res;
		}
		var worker = $.Deferred();
		cache.lock(key, res = worker.promise());
		var cached = cache.get(key);
		if (cached) {
			cache.unlock(key);
			worker.notify("CACHED " + key);
			worker.resolve(cached, "cached");
  	} else if (getFunc) {
  		var unlock = true;
  		var get = getFunc(key);
  		get.done(function(data, status, textStatus, jqXHR) {
  			cache.put(key, data);
  			cache.unlock(key);
  			unlock = false;
  			worker.resolve(data, status, textStatus, jqXHR);
	  	});
  		get.fail(function(data, status, err, jqXHR) {
  			cache.unlock(key);
  			unlock = false;
  			worker.reject(data, status, err, jqXHR);
  		});
  		get.always(function() {
  			if (unlock) {
  				// unlock again here - for a case if will not do in done/fail :)
  				cache.unlock(key); 
  			}
  		});
  	} else {
  		cache.unlock(key);
  		worker.notify("NOT FOUND " + key + ". Getter function not provided.");
  		worker.reject("Not found: " + key);
  	}
  	return res;
	};
	
	var cachedUsers = new Cache();
	var getUserInfoReq = function(userId) {
		var request = $.ajax({
			async : true,
			type : "GET",
			url : prefixUrl + "/portal/rest/webconferencing/user/" + userId
		});
		return initRequest(request);
	};
	var getUserInfo = function(userId) {
		return getCached(userId, cachedUsers, getUserInfoReq);
	};

	// Local caches not yet used, but still good thing to add as an improvements
	var cachedSpaces = new Cache();
	var getSpaceInfoReq = function(spaceId) {
		var request = $.ajax({
			async : true,
			type : "GET",
			url : prefixUrl + "/portal/rest/webconferencing/space/" + spaceId
		});
		return initRequest(request);
	};
	var getSpaceInfo = function(spaceId) {
		return getCached(spaceId, cachedSpaces, getSpaceInfoReq);
	};
	
	var cachedRooms = new Cache();
	var getRoomInfoReq = function(roomRef, title, members) {
		var q = "";
		if (title) {
			q += "?title=" + encodeURIComponent(title); 
		}
		if (members && members.length > 0) {
			if (q.length == 0) {
				q += "?";
			} else {
				q += "&";
			}
			q += "members=" + encodeURIComponent(members.join(";"));
		}
		var request = $.ajax({
			async : true,
			type : "GET",
			url : prefixUrl + "/portal/rest/webconferencing/room/" + roomRef + q
		});
		return initRequest(request);
	};
	var getRoomInfo = function(id, name, title, members) {
		return getCached(name + "/" + id, cachedRooms, function(key) {
			return getRoomInfoReq(key, title, members);
		});
	};
	
	var getProvidersConfig = function() {
		var request = $.ajax({
			async : true,
			type : "GET",
			url : prefixUrl + "/portal/rest/webconferencing/providers/configuration"
		});
		return initRequest(request);
	};
	
	var getProviderConfig = function(type) {
		var request = $.ajax({
			async : true,
			type : "GET",
			url : prefixUrl + "/portal/rest/webconferencing/provider/" + type + "/configuration"
		});
		return initRequest(request);
	};
	
	var postProviderConfig = function(type, active) {
		var request = $.ajax({
			async : true,
			type : "POST",
			url : prefixUrl + "/portal/rest/webconferencing/provider/" + type + "/configuration",
			data : {
				active : active
			}
		});
		return initRequest(request);
	};
	
	var getUserStatus = function(userId) {
		var request = $.ajax({
			async : true,
			type : "GET",
			url : prefixUrl + "/portal/rest/state/status/" + userId
		});
		return initRequest(request);
	};
	
	var serviceGet = function(url, data, headers) {
		var request = $.ajax({
			async : true,
			type : "GET",
			url : url,
			dataType : "json",
			data : data ? data : undefined,
			headers : headers ? headers : undefined
		});
		return initRequest(request);
	};
	
	var prepareUser = function(user) {
		user.title = user.firstName + " " + user.lastName;
	};
	
	/**
	 * Helpers for interaction with eXo Chat on portal pages.
	 */
	function Chat() {
		
		var isApplication = function() {
			return typeof chatApplication == "object" && chatApplication;
		};
		this.isApplication = isApplication;
		
		var isEmbedded = function() {
			return typeof chatNotification == "object" && chatNotification;
		};
		this.isEmbedded = isEmbedded;
		
		this.currentRoomId = function() {
			if (isApplication()) {
				return chatApplication.targetUser;					
			} else {
				// TODO for mini chat we can find a room user via getRoom(id) but it will be in a promise
			}
		};
		
		this.getUsers = function(roomId) {
			var process = $.Deferred(); 

			var url, currentUser, dbName, token;
			
			if (isApplication()) {
				if (!roomId) {
					roomId = chatApplication.targetUser;					
				}
				url = chatApplication.jzUsers;
				currentUser = chatApplication.username;
				dbName = chatApplication.dbName;
				token = chatApplication.token;
			} else if (isEmbedded()) {
				if (!roomId) {
					roomId = jzGetParam(chatNotification.sessionId + "miniChatRoom");
				}
				var $chatStatus = $("#chat-status");
				if ($chatStatus.length > 0) {
					var serverUrl = $chatStatus.data("chat-server-url");
					if (serverUrl) {
						url = serverUrl + "/users";
					} else {
						process.reject("Cannot determine Chat server URL"); 
					}
					currentUser = chatNotification.username;
					dbName = chatNotification.dbName;
					token = chatNotification.token;
				} else {
					process.reject("Chat credentials not found");
				}
			} else {
				process.reject("Chat not found");
			}
			
			if (url && roomId) {
				serviceGet(url, {
	        room: roomId,
	        user: currentUser,
	        dbName: dbName
	      }, {
	        "Authorization": "Bearer " + token
	      }).done(function(resp) {
	      	if (resp && resp.users) {
						process.resolve(resp.users);																
					} else {
						process.reject("Chat users request return empty response");
					}
	      }).fail(function(err, status) {
	      	process.reject(err, status);
	      });				
			} else if (process.state() == "pending") {
				process.reject("Cannot get room users: prerequisites failed");
			}
			
			return process.promise();
		};
		
		/**
		 * Gets a chat room by its ID and type. If ID not given then it will try find a current one from the context.
		 * If type not given but ID fond then it will try autodetect it. Method always returns a promise, it will be resolved if room request
		 * succeeded, if no room found then it resolved with null value, and rejected if some parameter wrong or request failed.
		 */
		this.getRoom = function(id, type) {
			var process = $.Deferred(); 

			var url, currentUser, dbName, token;
			
			if (isApplication()) {
				if (!id) {
					id = chatApplication.targetUser;					
				}
				if (!id) {
					process.reject(null);
				}
				url = chatApplication.jzChatGetRoom;
				currentUser = chatApplication.username;
				dbName = chatApplication.dbName;
				token = chatApplication.token;
			} else if (isEmbedded()) {
				if (!id) {
					id = jzGetParam(chatNotification.sessionId + "miniChatRoom");
					type = jzGetParam(chatNotification.sessionId + "miniChatType");
				}
				if (!id) {
					process.reject(null);
				}
				var $chatStatus = $("#chat-status");
				if ($chatStatus.length > 0) {
					var serverUrl = $chatStatus.data("chat-server-url");
					if (serverUrl) {
						url = serverUrl + "/getRoom";
					} else {
						process.reject("Cannot determine Chat server URL"); 
					}
					currentUser = chatNotification.username;
					dbName = chatNotification.dbName;
					token = chatNotification.token;
				} else {
					process.reject("Chat credentials not found");
				}
			} else {
				process.reject("Chat not found");
			}
			
			if (url && id) {
				var roomReq = {
					targetUser: id,
					user: currentUser,
					dbName: dbName,
					withDetail: true,
					isAdmin : false
				};
				// If type not given then the ID should be in prefix form, i.e. starts with space-, team- or external-
				// otherwise it will be treated as users room (1:1) by the Chat server
				if (typeof type == "string") {
					roomReq.type = type;
				}
				serviceGet(url, roomReq, {
				  "Authorization": "Bearer " + token
				}).done(function(room) {
					process.resolve(room);
				}).fail(function(err, status) {
					process.reject(err, status);
				});				
			} else if (process.state() == "pending") {
				process.reject("Cannot get chat room: prerequisites failed");
			}
			
			return process.promise();
		};
	}
	
	/**
	 * WebConferencing core class.
	 */
	function WebConferencing() {

		var self = this;
		
		// ******** Context ********
		var contextInitializer = $.Deferred();
		var currentUser, currentSpaceId, currentRoomTitle;

		// Providers
		var providers = []; // loaded providers
		var providersConfig; // will be assigned in init()
		var providersInitializer = {}; // map managed by getProvider() and initProvider()
		
		var chat = new Chat();
		this.getChat = function() {
			return chat;
		};

		this.errorText = errorText;
		
		var contextId = function(context) {
			return context.userId ? context.userId : (context.spaceId ? context.spaceId : context.roomName);
		};
		this.contextId = contextId;
		
		var userPreferenceKey = function(name) {
			return currentUser.id + "@exo.webconferencing." + name;
		};
		
		var getPreferredProvider = function(contextName) {
			if (currentUser) {
				var key = userPreferenceKey(contextName + ".provider");
				if (typeof Storage != "undefined") {
					return localStorage.getItem(key);
				} else {
				  // No Web Storage support.
					if (eXo && eXo.webConferencing && eXo.webConferencing.__preferences) {
						return eXo.webConferencing.__preferences[key];
					} else {
						log.warn("Cannot read user preference: local storage not supported.");
					}
				}				
			} else {
				log.warn("Current user not set for reading user preferences.");
			}
			return null;
		};
		
		var setPreferredProvider = function(contextName, providerType) {
			if (currentUser) {
				var key = userPreferenceKey(contextName + ".provider");
				if (typeof Storage != "undefined") {
					localStorage.setItem(key, providerType);
				} else {
				  // No Web Storage support.
					if (eXo && eXo.webConferencing && eXo.webConferencing.__preferences) {
						eXo.webConferencing.__preferences[key] = providerType;
					} else {
						log.warn("Cannot save user preference: local storage not supported.");
					}
				}				
			} else {
				log.warn("Current user not set for saving user preferences.");
			}
		};
		
		var initContext = function() {
			var context = {
				currentUser : currentUser,
				isIOS : isIOS,
				isAndroid : isAndroid,
				isWindowsMobile : isWindowsMobile,
				details : function() {
					// this method should not be used in this context, thus keep it for unification only
					var data = $.Deferred();
					data.resolve([], context.space.id, context.space.title);
					return data.promise();
				}
			};
			if (currentSpaceId) {
				context.spaceId = currentSpaceId; 
				context.isSpace = true;
				context.isGroup = true;
			} else {
				context.spaceId = null;
				context.isSpace = false;
				context.isGroup = false;
			}
			if (currentRoomTitle) {
				context.roomTitle = currentRoomTitle;
				context.isRoom = true; 
			} else {
				context.roomTitle = null;
				context.isRoom = false;
			}
			return context;
		};

		var providerConfig = function(type) {
			for (var i=0; i<providersConfig.length; i++) {
				var conf = providersConfig[i];
				if (conf && conf.type == type) {
					return conf;
				}
			}
			return null;
		};
		
		var initProvider = function(provider) {
			// Returned promise will be resolved with a provider instance and boolean flag indicating was the provider 
			// successfully initialized or not. The promise will be rejected if provider not configured (should not happen).
			var initializer = providersInitializer[provider.getType()]; // deferred may be added by getProvider()
			if (!initializer) {
				initializer = providersInitializer[provider.getType()] = $.Deferred();
			}
			var conf = providerConfig(provider.getType());
			if (conf) {
				provider.isInitialized = false;
				initializer.progress(provider); // here is a provider that has a configuration
				if (conf.active) {
					if (provider.init && provider.hasOwnProperty("init")) {
						provider.init(initContext()).done(function() {
							provider.isInitialized = true;
							log.debug("Initialized call provider: " + provider.getType());
							initializer.resolve(provider, true);
						}).fail(function(err) {
							log.warn("Failed to initialize call provider '" + provider.getType() + "'", err);
							initializer.resolve(provider, false);
						});
					} else {
						log.debug("Marked call provider as Initialized: " + provider.getType());
						provider.isInitialized = true;
						initializer.resolve(provider, true);
					}
				} else {
					log.debug("CANCELED initialization of not active call provider '" + provider.getType() + "'");
					initializer.resolve(provider, false);
				}
			} else {
				log.warn("CANCELED initialization of not configured call provider '" + provider.getType() + "'");
				initializer.reject(provider.getType() + " " + message("notConfigured"));
			}
			return initializer.promise();
		};
		
		/**
		 * Add call button to given target element.
		 */
		var addCallButton = function($target, context) {
			var initializer = $.Deferred();
			if ($target.length > 0) {
				// We need deal with non consecutive asynchronous calls to this method,
				// 1) use only currently available providers - froze the state
				var addProviders = providers.slice();
				if (addProviders.length > 0) {
					var buttonClass = "callButton";
					var providerFlag = "hasProvider_";
					var contextName = contextId(context);
					// 2) if already calling, then need wait for previous call completion and then re-call this method 
					var prevInitializer = $target.data("callbuttoninit");
					if (prevInitializer) {
						log.trace(">>> addCallButton > init WAIT " + contextName + " providers: " + addProviders.length);
						prevInitializer.always(function() {
							log.trace(">>> addCallButton > init RESUMED " + contextName + " providers: " + addProviders.length);
							addCallButton($target, context).done(function($container) {
								initializer.resolve($container);
							}).fail(function(err) {
								initializer.reject(err);
							});
						});
					} else {
						$target.data("callbuttoninit", initializer);
						//log.trace(">>> addCallButton > init " + contextName + " providers: " + addProviders.length);
						initializer.always(function() {
							$target.removeData("callbuttoninit");
							//log.trace("<<< addCallButton < init " + contextName + " providers: " + addProviders.length);
						});
						// Call button placed in a 'container' element for positioning on a page
						// TODO may be we don't need it since we don't have a default button?
						var $container = $target.find(".callButtonContainer");
						// Dropdown is a button to click when several providers available, otherwise it's a simple button
						var $dropdown = $container.find(".dropdown-menu");
						var addDropdown = $dropdown.length == 0;
						var hasButton = $container.children("." + buttonClass).length > 0;
						if ($container.length == 0) {
							$container = $("<div style='display: none;' class='callButtonContainer'></div>");
							$target.append($container);
						} // else, a first (single) button or several (in the dropdown) already exist
						var workers = [];
						var preferredProviderId = getPreferredProvider(contextName);
						var preferredClass = "preferred";
						var $preferredButton = $container.find("." + preferredClass).first(); // it should be an one in fact
						function moveToDefaultButton($preferred) {
							if ($dropdown.length > 0 && $preferred.length > 0) {
								var $first = $container.find(".btn." + buttonClass);
								if ($first.is($preferred)) {
									if (!$preferred.hasClass(preferredClass)) {
										$preferred.addClass(preferredClass);
									}
								} else {
									// if not the same selected element in DOM
									$first.removeClass("btn");
									$first.removeClass(preferredClass);
									var $li = $("<li></li>");
									$li.append($first);
									$dropdown.prepend($li);
									$preferred.addClass("btn");
									$preferred.addClass(preferredClass);
									$container.prepend($preferred);
								} // else, preferred button already first
							} // else, nothing to move at all
						}
						function addProviderButton(provider, button) {
							//log.trace(">>> addCallButton > adding > " + contextName + "(" + provider.getTitle() + ") for " + context.currentUser.id);
							// need do this in a function to keep worker variable in the scope of given button when it will be done 
							var bworker = $.Deferred();
							button.done(function($button) {
								if (hasButton) {
									// add this button as an item to dropdown list
									//log.trace(">>> addCallButton > add in dropdown > " + contextName + "(" + provider.getTitle() + ") for " + context.currentUser.id);
									if ($dropdown.length == 0) { // check actual dropdown content right here, not addDropdown
										$dropdown = $("<ul class='dropdown-menu'></ul>");
									}
									$button.addClass(buttonClass);
									var $li = $("<li></li>");
									$li.append($button);
									$dropdown.append($li);
								} else {
									// add as a first (single) button
									//log.trace(">>> addCallButton > add first & default button > " + contextName + "(" + provider.getTitle() + ") for " + context.currentUser.id);
									if (!$button.hasClass("btn")) {
										$button.addClass("btn"); // btn btn-primary actionIcon ?
									}
									$button.addClass(buttonClass);
									$container.append($button);
									hasButton = true;
								}
								if (provider.getType() == preferredProviderId) {
									// Mark if it's preferred button 
									// even if $preferredButton already contains something - this last wins (but this should not be a case)
									$preferredButton = $button; 
								} else {
									// Otherwise save user preference for this call	when it will be used
									$button.click(function() {
										setPreferredProvider(contextName, provider.getType());
										// Also reorder the Call Button and its dropdown to keep this one as default
										// TODO need also re-run context initializer consumed this promise's done button
										//moveToDefaultButton($button);
									});
								}
								log.trace("<<< addCallButton DONE < " + contextName + "(" + provider.getTitle() + ") for " + context.currentUser.id);
							});
							button.fail(function(msg, err) {
								log.trace("<<< addCallButton CANCELED < " + contextName + "(" + provider.getTitle() + ") for " + context.currentUser.id + ": " + msg);
								if (err) {
									log.error("Failed to add a call button for " + contextName + " by " + context.currentUser.id + ". " + msg + ". " + errorText(err));
								}
							});
							button.always(function() {
								// for the below $.when's always callback we need resolve all workers independently succeeded or failed 
								bworker.resolve();
							});
							workers.push(bworker.promise());
						}
						// we have an one button for each provider
						//log.trace(">>> addCallButton > " + contextName + " for " + context.currentUser.id + " providers: " + addProviders.length);
						for (var i = 0; i < addProviders.length; i++) {
							var p = addProviders[i];
							//log.trace(">>> addCallButton > next provider > " + contextName + "(" + p.getTitle() + ") for " + context.currentUser.id + " providers: " + addProviders.length);
							if (p.isInitialized) {
								if ($container.data(providerFlag + p.getType())) {
									//log.trace("<<< addCallButton DONE (already) < " + contextName + "(" + p.getTitle() + ") for " + context.currentUser.id);
								} else {
									// even if adding will fail, we treat it as 'canceled' and mark the provider as added
									$container.data(providerFlag + p.getType(), true);
									var b = p.callButton(context);
									addProviderButton(p, b);
								}								
							} else {
								log.trace("<<< addCallButton CANCELED (not initialized) < " + contextName + "(" + p.getTitle() + ") for " + context.currentUser.id);
							}
						}
						if (workers.length > 0) {
							$.when.apply($, workers).always(function() {
								if ($dropdown.length > 0) {
									if (addDropdown) {
										var $toggle = $("<button class='btn dropdown-toggle' data-toggle='dropdown'>" +
												"<i class='uiIconArrowDown uiIconLightGray'></i></span></button>");
										$container.append($toggle);
										$container.append($dropdown);
									}
									// User preferred provider for this call should be a default (first) button
									if (preferredProviderId && $preferredButton.length > 0) {
										moveToDefaultButton($preferredButton);
									} else {
										// Mark first a default one (for nice CSS)
										$container.find(".btn." + buttonClass).addClass(preferredClass);
									}
								} else {
									$container.find(".btn." + buttonClass).addClass(preferredClass);
								}
								$container.show();
								initializer.resolve($container);
			        });
						} else {
							initializer.reject(); // Nothing to add
						}
					}
				} else {
					initializer.reject("No providers");
				}	
			} else {
				initializer.reject("Target not found");
			}
			return initializer.promise();
		};
		
		/**
		 * Find current Chat context from a room available in it.
		 */
		var getChatContext = function() {
			var process = $.Deferred();
			chat.getRoom().done(function(room) {
				if (room) {
					var roomId = room.user;
					var roomTitle = room.fullName;
					
					var isSpace = room.type == "s"; // roomId && roomId.startsWith("space-");
					var isRoom =  room.type == "t"; // roomId && roomId.startsWith("team-");
					var isGroup = isSpace || isRoom;
					var isUser = !isGroup && room.type == "u";
					
					// It is a logic used in Chat, so reuse it here:
					var roomName = roomTitle.toLowerCase().split(" ").join("_");
					var context = {
						currentUser : currentUser,
						roomId : roomId,
						roomName : roomName, // has no sense for team rooms, but for spaces it's pretty_name
						roomTitle : roomTitle,
						isGroup : isGroup,
						isSpace : isSpace,
						isRoom : isRoom,
						isUser : isUser,
						isIOS : isIOS,
						isAndroid : isAndroid,
						isWindowsMobile : isWindowsMobile,
						details : function() {
							var data = $.Deferred();
						  if (isGroup) {
						  	if (isSpace) {
						  		var spaceId = roomName; // XXX no other way within Chat
							  	getSpaceInfoReq(spaceId).done(function(space) { // TODO use getSpaceInfo() for caching spaces
							  		data.resolve(space);
							  	}).fail(function(e, status) {
							  		if (typeof status == "number" && status == 404) {
											log.trace(">> chatContext < ERROR get_space " + spaceId + " for " + currentUser.id + ": " + (e.message ? e.message + " " : "Not found ") + spaceId + ": " + JSON.stringify(e));
										} else {
											log.trace(">> chatContext < ERROR get_space " + spaceId + " for " + currentUser.id, e);
										}
							  		data.reject(e);
									});
						  	} else if (isRoom) {
						  		chat.getUsers(roomId).done(function(users) {
							  		var unames = [];
										for (var i=0; i<users.length; i++) {
											var u = users[i];
											if (u && u.name && u.name != "null") {
												unames.push(u.name);
											}
										}
										getRoomInfoReq(roomName + "/" + roomId, roomTitle, unames).done(function(info) {
											data.resolve(info);												
										}).fail(function(e, status) {
											if (typeof status == "number" && status == 404) {
												var msg = (e.message ? e.message + " " : "Not found ");
												log.trace(">> chatContext < ERROR get_room " + roomName + " (" + msg + ") for " + currentUser.id + ": " + (e.message ? e.message + " " : "Not found ") + roomName + ": " + JSON.stringify(e));
												data.reject(msg);
											} else {
												log.trace(">> chatContext < ERROR get_room " + roomName + " for " + currentUser.id + ": " + JSON.stringify(e));
												data.reject(e);
											}
											// TODO notify the user?
										});
							  	}).fail(function(err) {
							  		log.trace("Error reading Chat room users " + roomId, err);
										data.reject("Error reading Chat room users for " + roomId, err);
							  	});
						  	} else {
						  		log.trace(">> chatContext < ERROR unexpected chat type '" + chatType + "' and room '" + roomId + "' for " + currentUser.id);
						  		data.reject("Unexpected chat type: " + chatType);
						  	}
							} else {
								// roomId is an user name for P2P chats
								getUserInfoReq(roomId).done(function(user) {
									data.resolve(user);												
								}).fail(function(e, status) {
									if (typeof status == "number" && status == 404) {
										var msg = (e.message ? e.message + " " : "Not found ");
										log.trace(">> chatContext < ERROR get_user " + msg + " for " + currentUser.id + ": " + JSON.stringify(e));
										data.reject(msg);
									} else {
										log.trace(">> chatContext < ERROR get_user : " + JSON.stringify(e));
										data.reject(e);
									}
								});
							}
							return data.promise();
						}
					};
					process.resolve(context);
				} else {
					// If no room, then resolve with 'empty' context 
					process.resolve({
						currentUser : currentUser,
						isIOS : isIOS,
						isAndroid : isAndroid,
						isWindowsMobile : isWindowsMobile
					});
				}
			}).fail(function(err) {
				process.reject(err);
			});
			return process.promise();
		};
		
		/**
		 * eXo Chat initialization
		 */
		var initChat = function() {
			$(function() {
				var $chat = $("#chat-application");
				// chatApplication is global on chat app page
				if (chat.isApplication() && $chat.length > 0) {
					log.trace(">> initChat for " + chatApplication.username);
					var $roomDetail = $chat.find("#room-detail");
					var addRoomButtton = function() {
						$roomDetail.find(".callButtonContainerWrapper").hide(); // hide immediately
						setTimeout(function() {
							var $teamDropdown = $roomDetail.find(".chat-team-button-dropdown");
							if ($teamDropdown.length > 0) {
								var $wrapper = $roomDetail.find(".callButtonContainerWrapper");
								if ($wrapper.length > 0) {
									$wrapper.empty();
								} else {
									$wrapper = $("<div class='callButtonContainerWrapper pull-right' style='display: none;'></div>");
									$teamDropdown.after($wrapper);
								}
								getChatContext().done(function(context) {
									if (context.isSpace) {
										// When in Chat app we set current space ID from the current room.
										// It may be used by the provider module by calling webConferencing.getCurrentSpaceId()
										// XXX here we use the same technique as in chat.js's loadRoom(), 
										// here space pretty name is an ID
										currentRoomTitle = context.roomTitle;
										currentSpaceId = context.roomName;
									} else if (context.isRoom) {
										currentRoomTitle = context.roomTitle;
										currentSpaceId = null;
									} else if (context.isUser) {
										currentRoomTitle = context.roomTitle;
										currentSpaceId = null;
									} else {
										// It's no chat room found
										currentRoomTitle = null;
										currentSpaceId = null;
									}
									if (context.roomId) {
										var initializer = addCallButton($wrapper, context);
										initializer.done(function($container) {
											$container.find(".callButton").first().addClass("chatCall");
											$container.find(".dropdown-menu").addClass("pull-right");
											$wrapper.show();
											log.trace("<< initChat DONE " + context.roomTitle + " for " + currentUser.id);
										});
										initializer.fail(function(err) {
											if (error) {
												log.trace("<< initChat ERROR " + context.roomTitle + " for " + currentUser.id, err);
												log.error("Chat initialization failed in '" + context.roomTitle + "' for " + currentUser.id, err);
												$roomDetail.removeData("roomcallinitialized");
											}
										});
									} else {
										log.trace("<< initChat WARN no room found in context");
										$roomDetail.removeData("roomcallinitialized");
									}
								}).fail(function(err) {
									log.trace("<< initChat ERROR getting room info from chatServer", err);
									log.error("Error getting room info from Chat server", err);
									$roomDetail.removeData("roomcallinitialized");
								}); 
							} else {
								//log.trace("Chat team dropdown not found");
								$roomDetail.removeData("roomcallinitialized");
							}
						}, 1500); // XXX whoIsOnline may run 500-750ms on eXo Tribe
					};
					
					if (!$roomDetail.data("roomcallinitialized")) {
						$roomDetail.data("roomcallinitialized", true);
						addRoomButtton();
					} else {
						log.trace("Chat room already initialized");
					}
					
					// User popovers in right panel
					var $chatUsers = $chat.find("#chat-users");
					$chatUsers.each(function(index, elem) {
						var $target = $(elem);
						if (!$target.data("usercallinitialized")) {
							$target.data("usercallinitialized", true);
							$target.click(function() {
								$roomDetail.removeData("roomcallinitialized");
								addRoomButtton();
							});
						}
					});
				}
			});
		};
		
		var initMiniChat = function() {
			var $miniChat = $(".mini-chat").first();
			var $fullName = $miniChat.find(".fullname");
			if (typeof chatApplication === "undefined" && $fullName.length > 0 && chatNotification) {
				if ($miniChat.data("minichatcallinitialized")) {
					//log.trace("<< initMiniChat CANCELED < Already initialized [" + $fullName.text().trim() + "] for " + currentUser.id);
				} else {
					$miniChat.data("minichatcallinitialized", true);
					var process = $.Deferred();
					var addMiniChatCallButton = function() {
						var roomTitle = $fullName.text().trim();
						log.trace(">> initMiniChat [" + roomTitle + "] for " + currentUser.id);
						var $titleBar = $miniChat.find(".title-right");
						if ($titleBar.length > 0 && roomTitle.length > 0) {
							var $wrapper = $miniChat.find(".callButtonContainerMiniWrapper");
							$wrapper.children().remove(); // clean the previous state, if have one
							// Wait a bit for completing Chat workers
							setTimeout(function() {
								getChatContext().done(function(context) {
									if (context.roomId) {
										if ($wrapper.length == 0) {
											$wrapper = $("<div class='callButtonContainerMiniWrapper pull-left' style='display: inline-block;'></div>");											
										}
										var initializer = addCallButton($wrapper, context);
										initializer.done(function($container) {
											var $first = $container.find(".callButton").first();
											$first.removeClass("btn").addClass("uiActionWithLabel btn-mini miniChatCall");
											$first.children(".callTitle").remove();
											$first.children(".uiIconLightGray").add($container.find(".dropdown-toggle > .uiIconLightGray"))
													.removeClass("uiIconLightGray").addClass("uiIconWhite");
											$container.find(".dropdown-toggle").removeClass("btn").addClass("btn-mini");
											$container.find(".dropdown-menu").addClass("pull-right");
											$titleBar.prepend($wrapper);
											log.trace("<< initMiniChat DONE [" + context.roomTitle + "] for " + currentUser.id);
										});
										initializer.fail(function(err) {
											if (err) {
												log.trace("<< initMiniChat ERROR [" + context.roomTitle + "] for " + currentUser.id, err);
												log.error("Mini-chat initialization failed in " + context.roomTitle + " for " + currentUser.id, err);
												$miniChat.removeData("minichatcallinitialized");												
											}
										});
									} else {
										log.trace("<< initMiniChat WARN no room found in context");
									}
								}).fail(function(err) {
									log.trace("<< initMiniChat ERROR getting room info from chatServer", err);
									log.error("Error getting room info from Chat server", err);
								});
							}, 750);
						} else {
							log.trace("<< initMiniChat CANCELED mini-chat not found or empty");
						}
					};
					//addMiniChatCallButton(); // TODO need this, or observer's one will be enough?
					// run DOM listener to know when mini chat will be completed (by notif.js script)
					var MutationObserver = window.MutationObserver || window.WebKitMutationObserver || window.MozMutationObserver;
					var observer = new MutationObserver(function(mutations) {
						addMiniChatCallButton();
					});
					observer.observe($fullName.get(0), {
						subtree : false,
						childList : true,
						attributes : false,
						characterData : false
					});
				};
			}
		};

		var userContext = function(userId) {
			var context = {
				currentUser : currentUser,
				userId : userId,
				isGroup : false,
				isSpace : false,
				isRoom : false,
				isUser : true,
				isIOS : isIOS,
				isAndroid : isAndroid,
				isWindowsMobile : isWindowsMobile,
				details : function() {
					var user = getUserInfoReq(userId);
					user.fail(function(e, status) {
						if (typeof status == "number" && status == 404) {
							log.trace(">> userContext < ERROR get_user " + (e.message ? e.message + " " : "Not found ") + userId + " for " + currentUser.id + ": " + JSON.stringify(e));
						} else {
							log.trace(">> userContext < ERROR get_user : " + JSON.stringify(e));
						}
					});
					return user;
				}
			};
			return context;
		};
		
		var tiptip = function() {
			var process = $.Deferred();
			function findTiptipWait() {
				var $tiptip = $("#tiptip_content");
				if ($tiptip.length == 0) {
					// wait for popup script load
					setTimeout(findTiptipWait, 250);
				} else {
					process.resolve($tiptip);
				}
			}
			findTiptipWait();
			return process.promise();
		};
		
		var onTiptipUpdate = function(listener) {
			tiptip().done(function($tiptip) {
				var listeners = $tiptip.data("callbuttoninit");
				if (listeners && listeners.length > 0) {
					listeners.push(listener);
				} else {
					var listeners = [ listener ];
					$tiptip.data("callbuttoninit", listeners);
					// run DOM listener to know when popovr will be updated with actual (context) content
					// we catch '#tiptip_content #tipName node addition
					var MutationObserver = window.MutationObserver || window.WebKitMutationObserver || window.MozMutationObserver;
					var findNodeById = function(id, list) {
						for (var i=0; i<list.length; i++) {
							var n = list[i];
							if (n.id == id) {
								return n;
							}
						}
						return null;
					}
					var observer = new MutationObserver(function(mutations) {
						//log.trace(">>> onTiptipUpdate mutations " + mutations.length);
						for(var i=0; i<mutations.length; i++) {
							var m = mutations[i];
							var tipName;
							if (m.type == "childList" && (tipName = findNodeById("tipName", m.addedNodes))) {
								var $tipName = $(tipName);
								for (var i=0; i<listeners.length; i++) {
									listeners[i]($tiptip, $tipName);
								}
								break;
							}
						}
					});
					observer.observe($tiptip.get(0), {
						childList : true,
						subtree : false,
						attributes : false
					});
				}
			});
		};
		
		var addPopoverButton = function($target, context) {
			var initializer = addCallButton($target, context);
			initializer.done(function($container) {
				$container.find(".callButton").first().addClass("popoverCall");
				// XXX workaround to avoid first-child happen on call button in the popover
				$container.prepend($("<div class='btn' style='display: none;'></div>"));
				log.trace("<< addPopoverButton DONE " + contextId(context) + " for " + currentUser.id);
			});
			initializer.fail(function(err) {
				if (err) {
					log.trace("<< addPopoverButton ERROR " + contextId(context) + " for " + currentUser.id , err);
				}
			});
			return initializer;
		};
		
		/**
		 * Add call button to user's on-mouse popups and panels.
		 */
		var initUsers = function() {
			// user popovers
			onTiptipUpdate(function($tiptip, $tipName) {
				var $profileLink = $tipName.find("td>a[href*='\\/profile\\/']");
				if ($profileLink.length > 0) {
					// Find user ID for a tip
					var userId = $profileLink.attr("href");
					userId = userId.substring(userId.lastIndexOf("/") + 1, userId.length);
					if (userId != currentUser.id) {
						var $userAction = $tiptip.find(".uiAction");
						var buttonUser = $userAction.data("callbuttonuser");
						if (!buttonUser || buttonUser != userId) {
							$userAction.data("callbuttonuser", userId);
							// cleanup after previous user
							$userAction.find(".callButtonContainer").empty();
							addPopoverButton($userAction, userContext(userId));
						}
					}
				}
			});

			// single user profile;
			var $userProfileMenu = $(".uiProfileMenu:first");
			var userId = $userProfileMenu.find(".profileMenuNavHeader h3").data("userid");
			if (userId != currentUser.id) {
				var $userActions = $userProfileMenu.find(".profileMenuApps");
				var $callButtons = $userActions.find(".userMenuCallButtons");
				if ($callButtons.length == 0) {
					$callButtons = $("<li></li>").appendTo($userActions).addClass("userMenuCallButtons");
				}
				addCallButton($callButtons, userContext(userId)).done(function($container) {
					$container.addClass("pull-left");
					log.trace("<< initUsers profile DONE " + userId + " for " + currentUser.id);
				});
			}
		};
		
		var spaceContext = function(spaceId) {
			var context = {
				currentUser : currentUser,
				spaceId : spaceId,
				isGroup : true,
				isSpace : true,
				isRoom : false,
				isUser : false,
				isIOS : isIOS,
				isAndroid : isAndroid,
				isWindowsMobile : isWindowsMobile,
				details : function() {
					var space = getSpaceInfoReq(spaceId); // TODO use getSpaceInfo() for caching spaces
			  	space.fail(function(e, status) {
						if (typeof status == "number" && status == 404) {
							log.trace(">> spaceContext < ERROR get_space " + spaceId + " for " + currentUser.id + ": " + (e.message ? e.message + " " : "Not found ") + spaceId + ": " + JSON.stringify(e));
						} else {
							log.trace(">> spaceContext < ERROR get_space " + spaceId + " for " + currentUser.id + ": " + JSON.stringify(e));
						}
					});
					return space;
				}
			};
			return context;
		};
		
		/**
		 * Add call button to space's on-mouse popups and panels.
		 */
		var initSpacePopups = function() {
			// space popovers
			onTiptipUpdate(function($tiptip, $tipName) {
				// Find user's first name for a tip
				var $profileLink = $tipName.find("td>a[href*='\\/g/:spaces:']");
				if ($profileLink.length > 0) {
					var spaceId = $profileLink.attr("href");
					spaceId = spaceId.substring(spaceId.lastIndexOf("/") + 1, spaceId.length);
					var $spaceAction = $tiptip.find(".uiAction");
					var buttonSpace = $spaceAction.data("callbuttonspace");
					if (!buttonSpace || buttonSpace != spaceId) {
						$spaceAction.data("callbuttonspace", spaceId);
						// cleanup after previous space
						$spaceAction.find(".callButtonContainer").empty();
						addPopoverButton($spaceAction, spaceContext(spaceId));
					}
				} else {
					log.trace("<< initSpacePopups WARN popover profileName link not found");
				}
			});
		};
		
		var initSpace = function() {
			if (currentSpaceId) {
				var $spaceMenuPortlet = $("#UISpaceMenuPortlet");
				var $spaceApps = $spaceMenuPortlet.find(".spaceMenuApps");
        var $callButtons = $spaceApps.find(".spaceMenuCallButtons");
				
				if ($callButtons.length == 0) {
					$callButtons = $("<li></li>").appendTo($spaceApps).addClass("spaceMenuCallButtons");
				}
				
				var addSpaceCallButton = function() {
					var initializer = addCallButton($callButtons, spaceContext(currentSpaceId));
					initializer.done(function($container) {
						var $button = $container.find(".callButton");
						var $first = $button.first();
						$first.addClass("spaceCall");
						log.trace("<< initSpace DONE " + currentSpaceId + " for " + currentUser.id);
					});
					initializer.fail(function(err) {
						if (err) {
							log.trace("<< initSpace ERROR " + currentSpaceId + " for " + currentUser.id, err);
							log.error("Space initialization failed in " + currentSpaceId + " for " + currentUser.id, err);
						}
					});
				};
				
				// XXX if Chat found, ensure Call button added after it to respect its CSS
				if (chatBundleData || $("#chat-status").length > 0) {
					var waitAttempts = 0;
					var waitAndAdd = function() {
						waitAttempts++;
						setTimeout(function() {
							var $chatButton = $spaceApps.children(".chat-button");
							if ($chatButton.length == 0 && waitAttempts < 40) { // wait max 2 sec
								waitAndAdd();
							} else {
								addSpaceCallButton();								
							}
						}, 50);						
					};
					waitAndAdd();
				} else {
					addSpaceCallButton();
				}
			}
		};
		
		this.update = function() {
			if (currentUser) { 
				initUsers();
				initSpacePopups();
				initSpace();
				initChat();
				initMiniChat();
			}
		};

		/**
		 * Initialize context
		 */
		this.init = function(user, context) {
			if (context) {
				messages = context.messages;
				if (user) {
					currentUser = user;
					currentUser.clientId = clientId;
					providersConfig = context.providersConfig;
					prepareUser(currentUser);
					if (context.spaceId) {
						currentSpaceId = context.spaceId;
					} else {
						currentSpaceId = null;
					}
					if (context.roomTitle) {
						currentRoomTitle = context.roomTitle;
					} else {
						currentRoomTitle = null; 
					}
					
					// init CometD connectivity
					if (context.cometdPath) {
						cCometD.configure({
							"url": prefixUrl  + context.cometdPath,
							"exoId": currentUser.id,
							"exoToken": context.cometdToken,
							"maxNetworkDelay" : 30000,
							"connectTimeout": 60000
						});
						cometd = cCometD;
						cometdContext = {
							"exoContainerName" : context.containerName,
							"exoClientId" : currentUser.clientId
						};
						cometd.onListenerException = function(exception, subscriptionHandle, isListener, message) {
					    // Uh-oh, something went wrong, disable this listener/subscriber
					    // Object "this" points to the CometD object
							log.error("CometD listener exception: " + exception + " (" + subscriptionHandle + ") isListener:" + isListener + " message:" + message);
					    if (isListener) {
					        this.removeListener(subscriptionHandle);
					    } else {
					        this.unsubscribe(subscriptionHandle);
					    }
						}
						
						// Check if need core log remote spooling. Cometd required also for remote logger.
						if (providersConfig && cometd) {
							for (var i=0; i<providersConfig.length; i++) {
								var conf = providersConfig[i];
								if (conf && conf.logEnabled) {
									// core log also should be spooled remotely from this moment, it contains info important for 
									// monitoring a provider functionality
									log = new Logger().prefix("webconferencing").remoteLog(true).get();
									break;
								}
							}
						}

						log.debug("User initialized in Web Conferencing: " + currentUser.id + ". Lang: " + (navigator.language || navigator.userLanguage || navigator.browserLanguage) 
									+ ". Local date: " + new Date().toLocaleString() + ". Browser: " + navigator.userAgent);
					} else {
						log.warn("CometD not found in context settings");
					}
				
					contextInitializer.resolve();
					
					// also init registered providers
					for (var i = 0; i < providers.length; i++) {
						var p = providers[i];
						if (!p.isInitialized) {
							initProvider(p);
						}
					}
				}
			}
		};
	
		/**
		 * eXo user running current session.
		 */
		this.getUser = function() {
			return currentUser;
		};
		
		/**
		 * A space currently open in a page that runs this script. 
		 * It is not a space of the context (call button etc.) - use contextual spaceId instead.
		 */
		this.getCurrentSpaceId = function() {
			return currentSpaceId;
		};
		
		/**
		 * A room currently open in a page that runs this script. 
		 * It is not a room of the context (call button etc.) - use contextual roomTitle or roomName instead.
		 * Note that this value will be initialized on Chat app page, but may not set in mini chat or any other Platform page.  
		 */
		this.getCurrentRoomTitle = function() {
			return currentRoomTitle;
		};
		
		this.getBaseUrl = function() {
			return pageBaseUrl();
		};
		
		/**
		 * Add provider to the scope.
		 */
		this.addProvider = function(provider) {
			// A Provider should support set of API methods:
			// * getType() - major call type name
			// * getSupportedTypes() - all supported call types
			// * getTitle() - human-readable title for UI
			// * callButton(context) - provider should offer an implementation of a Call button and call invoker in it, 
			// it returns a promise, when it resolved there will be a JQuery element of a button(s) container. 
			//
			// A provider may support following of API methods:
			// * init() - will be called when web conferencing user will be initialized in this.init(), this method returns a promise
			// * getDescription() - human-readable description for UI (use it if description from server configuration isn't enough)
			
			// TODO avoid duplicates, use map like?
			if (provider.getSupportedTypes && provider.hasOwnProperty("getSupportedTypes") && provider.getTitle && provider.hasOwnProperty("getTitle")) {
				if (provider.callButton && provider.hasOwnProperty("callButton")) {
					// we'll also care about providers added after Web Conferencing initialization, see this.init()
					providers.push(provider);
					log.trace("Added call provider: " + provider.getType() + " (" + provider.getTitle() + ")");
					if (currentUser) {
						if (!provider.isInitialized) {
							initProvider(provider);
						} else {
							log.trace("Already initialized provider: " + provider.getType());
						}
					} else {
						log.trace("Current user not set, later will try initialized provider: " + provider.getType());
					}
				} else {
					log.warn("Not compartible provider object (method callButton() required): " + provider.getTitle());
				}
			} else {
				log.warn("Not a provider object: " + JSON.stringify(provider));
			}
		};
		
		/**
		 * Return a provider registered by the type. This method doesn't check if provider was successfully configured and initialized.
		 */
		this.findProvider = function(type) {
			for (var i = 0; i < providers.length; i++) {
				var p = providers[i];
				var ptypes = p.getSupportedTypes();
				for (var ti = 0; ti < ptypes.length; ti++) {
					if (ptypes[ti] === type) {
						return p;
					}					
				}
			}
			return null;
		};
		
		/**
		 * Return a promise that will be resolved when a provider will be loaded (may be never if wrong name).
		 */
		this.getProvider = function(type) {
			var initializer = providersInitializer[type]; // deferred may be added by initProvider()
			if (!initializer) {
				initializer = providersInitializer[type] = $.Deferred();
			}			
			return initializer.promise();
		};
		
		/**
		 * Helper method to show call popup according the Web Conferencing spec.
		 */
		this.showCallPopup = function(url, name) {
			// FYI Core adopted from Video Calls v1 notif.js
			var aw = window.screen.availWidth; // screen.width
			var ah = window.screen.availHeight; // screen.height
			var w, h, top, left;
			if (aw > 760) {
				w = Math.floor(aw * 0.8);
			  h = Math.floor(ah * 0.8);
			  left = (aw/2)-(w/2);
			  top = (ah/2)-(h/2);	
			} else {
				w = aw;
			  h = ah;
			  left = 0;
			  top = 0;
			}
		  var callWindow = window.open(url, name, "toolbar=no,menubar=no,scrollbars=no,resizable=no,location=no,directories=no,status=no,"
		  			+ "width=" + w + ",height=" + h + ",top=" + top + ",left=" + left);
		  if (callWindow) {
		  	callWindow.focus();
		  }
		  return callWindow;
		};
		
		/** 
		 * Helper method to obtain the user IM account of given type.
		 */
		this.imAccount = function(user, type) {
			var ims = user.imAccounts[type];
			if (ims && ims.length > 0) {
				// TODO work with multiple IMs of same type
				return ims[0]; 
			} else {
				return null;
			}
		};
		
		// TODO move these calls to CometD
		this.getUserInfo = getUserInfoReq; 
		this.getSpaceInfo = getSpaceInfoReq;
		this.getRoomInfo = getRoomInfoReq;
		
		/**
		 * Get registered call from server side database.
		 */
		this.getCall = function(id) {
			if (cometd) {
				//log.trace(">> getCall:/webconferencing/calls:" + id + " - request published");
				var process = $.Deferred();
				var callProps = cometdParams({
					command : "get",
					id : id
				});
				cometd.remoteCall("/webconferencing/calls", callProps, function(response) {
					var result = tryParseJson(response);
					if (response.successful) {
						//log.trace("<< getCall:/webconferencing/calls:" + id + " - success: " + cometdInfo(response));
					  process.resolve(result, 200);
					} else {
						//log.trace("<< getCall:/webconferencing/calls:" + id + " - failure: " + cometdError(response));
						process.reject(result, 400);
					}
				});
				return process.promise();
			} else {
				log.trace("Getting call requires CometD. Was call: " + id);
				return $.Deferred().reject("CometD required").promise();
			}
		};
		
		/**
		 * Update call state in server side database.
		 */
		this.updateCall = function(id, state) {
			if (cometd) {
				//log.trace(">> updateCall:/webconferencing/calls:" + id + " - request published");
				var process = $.Deferred();
				var callProps = cometdParams({
					command : "update",
					id : id,
					state : state
				});
				cometd.remoteCall("/webconferencing/calls", callProps, function(response) {
					var result = tryParseJson(response);
					if (response.successful) {
						//log.trace("<< updateCall:/webconferencing/calls:" + id + " - success: " + cometdInfo(response));
					  process.resolve(result, 200);
					} else {
						//log.trace("<< updateCall:/webconferencing/calls:" + id + " - failure: " + cometdError(response));
						process.reject(result, 400);
					}
				});
				return process.promise();
			} else {
				log.trace("Updating call requires CometD. Was call: " + id);
				return $.Deferred().reject("CometD required").promise();
			}
		};
		
		/**
		 * Remove call in server side database.
		 */
		this.deleteCall = function(id) {
			if (cometd) {
				//log.trace(">> deleteCall:/webconferencing/calls:" + id + " - request published");
				var process = $.Deferred();
				var callProps = cometdParams({
					command : "delete",
					id : id
				});
				cometd.remoteCall("/webconferencing/calls", callProps, function(response) {
					var result = tryParseJson(response);
					if (response.successful) {
						//log.trace("<< deleteCall:/webconferencing/calls:" + id + " - success: " + cometdInfo(response));
					  process.resolve(result, 200);
					} else {
						//log.trace("<< deleteCall:/webconferencing/calls:" + id + " - failure: " + cometdError(response));
						process.reject(result, 400);
					}
				});
				return process.promise();
			} else {
				log.trace("Deleting call requires CometD. Was call: " + id);
				return $.Deferred().reject("CometD required").promise();
			}
		};
		
		/**
		 * Register call in server side database.
		 */
		this.addCall = function(id, callInfo) {
			if (cometd) {
				//log.trace(">> addCall:/webconferencing/calls:" + id + " - request published");
				var process = $.Deferred();
				var callProps = cometdParams($.extend(callInfo, {
					command : "create",
					id : id
				}));
				cometd.remoteCall("/webconferencing/calls", callProps, function(response) {
					var result = tryParseJson(response);
					if (response.successful) {
						//log.trace("<< addCall:/webconferencing/calls:" + id + " - success: " + cometdInfo(response));
					  process.resolve(result, 200);
					} else {
						//log.trace("<< addCall:/webconferencing/calls:" + id + " - failure: " + cometdError(response));
						process.reject(result, 400);
					}
				});
				return process.promise();
			} else {
				log.trace("Adding call requires CometD. Was call: " + id);
				return $.Deferred().reject("CometD required").promise();
			}
		};
				
		this.getUserGroupCalls = function() {
			if (cometd) {
				//log.trace(">> getUserGroupCalls:/webconferencing/calls - request published");
				var process = $.Deferred();
				var callProps = cometdParams({
					id : currentUser.id,
					command : "get_calls_state"
				});
				cometd.remoteCall("/webconferencing/calls", callProps, function(response) {
					var result = tryParseJson(response);
					if (response.successful) {
						//log.trace("<< getUserGroupCalls:/webconferencing/calls - success: " + cometdInfo(response));
					  process.resolve(result, 200);
					} else {
						//log.trace("<< getUserGroupCalls:/webconferencing/calls - failure: " + cometdError(response));
						process.reject(result, 400);
					}
				});
				return process.promise();
			} else {
				log.trace("Reading of user group calls requires CometD");
				return $.Deferred().reject("CometD required").promise();
			}
		};
		
		this.updateUserCall = function(id, state) {
			if (cometd) {
				// It's the same channel to call in CometD
				return this.updateCall(id, state);
			} else {
				log.trace("User call update requires CometD. Was call: " + id);
				return $.Deferred().reject("CometD required").promise();
			}
		};
		
		this.onUserUpdate = function(userId, onUpdate, onError, onReady) {
			if (cometd) {
				// /service/webconferencing/calls
				var subscription = cometd.subscribe("/eXo/Application/WebConferencing/user/" + userId, function(message) {
					// Channel message handler
					var result = tryParseJson(message);
					if (message.data.error) {
						if (typeof onError == "function") {
							onError(result, 400);
						}
					} else {
						if (typeof onUpdate == "function") {
							onUpdate(result, 200);
						}							
					}
				}, cometdContext, function(subscribeReply) {
					// Subscription status callback
					if (subscribeReply.successful) {
		        // The server successfully subscribed this client to the channel.
						log.trace("User updates subscribed successfully: " + JSON.stringify(subscribeReply));
						if (typeof onReady == "function") {
							onReady(subscribeReply);
						}
					} else {
						var err = subscribeReply.error ? subscribeReply.error : (subscribeReply.failure ? subscribeReply.failure.reason : "Undefined");
						log.debug("User updates subscription failed for " + userId, err);
						if (typeof onError == "function") {
							onError("User updates subscription failed (" + err + ")");								
						}
					}
				});
				return {
					off : function(callback) {
						cometd.unsubscribe(subscription, callback);
					}
				};
			} else {
				log.trace("User updates require CometD. Was user: " + userId);
				if (typeof onError == "function") {
					onError("CometD required");								
				}
				return {
					off : function(callback) {}
				};
			}
		};
		
		this.onCallUpdate = function(callId, onUpdate, onError, onReady) {
			if (cometd) {
				var subscription = cometd.subscribe("/eXo/Application/WebConferencing/call/" + callId, function(message) {
					// Channel message handler
					var result = tryParseJson(message);
					if (message.data.error) {
						if (typeof onError == "function") {
							onError(result);
						}
					} else {
						if (typeof onUpdate == "function") {
							onUpdate(result);
						}							
					}
				}, cometdContext, function(subscribeReply) {
					// Subscription status callback
					if (subscribeReply.successful) {
		        // The server successfully subscribed this client to the channel.
						log.trace("Call updates subscribed successfully: " + JSON.stringify(subscribeReply));
						if (typeof onReady == "function") {
							onReady(subscribeReply);
						}
					} else {
						var err = subscribeReply.error ? subscribeReply.error : (subscribeReply.failure ? subscribeReply.failure.reason : "Undefined");
						log.trace("Call updates subscription failed for " + callId, err);
						if (typeof onError == "function") {
							onError("Call updates subscription failed (" + err + ")");								
						}
					}
				});
				return {
					off : function(callback) {
						cometd.unsubscribe(subscription, callback);
					}
				};
			} else {
				log.trace("Call updates require CometD. Was call: " + callId);
				if (typeof onError == "function") {
					onError("Call updates require CometD");								
				}
				return {
					off : function() {}
				}
			}
		};
				
		this.toCallUpdate = function(callId, data) {
			var process = $.Deferred();
			if (cometd) {
				cometd.publish("/eXo/Application/WebConferencing/call/" + callId, cometdParams(data), function(publishAck) {
			    if (publishAck.successful) {
			    	//log.trace("<< Call update reached the server: " + JSON.stringify(publishAck));
			    	process.resolve("successful", 200);
			    } else {
			    	//log.trace("<< Call update failed to reach the server: " + JSON.stringify(publishAck));
			    	process.reject(publishAck.failure ? publishAck.failure.reason : publishAck.error);
			    }
				});
			} else {
				log.trace("Call updates require CometD. Was call: " + callId);
				process.reject("CometD required");
			}
			return process.promise();
		};
		
		this.getProvidersConfig = function(forceUpdate) {
			var process;
			if (!forceUpdate && providersConfig) {
				process = $.Deferred();
				process.resolve(providersConfig);
			} else {
				process = getProvidersConfig();
				process.done(function(configs) {
					providersConfig = configs;
				}).fail(function(err) {
					log.error("Loading providers configuration failed", err);
				});
			}
			return process.promise();
		}
		this.getProviderConfig = getProviderConfig; // this will ask server
		this.postProviderConfig = postProviderConfig;
		
		this.getUserStatus = getUserStatus;
		
		// common utilities
		this.getLog = function(providerType) {
			if (providerType) {
				if (providersConfig) {
					var conf = providerConfig(providerType);
					if (conf) {
						if (!conf.log) {
							conf.log = new Logger().provider(providerType).remoteLog(conf.logEnabled && cometd).get();
						}
						return conf.log;
					} else {
						log.warn("Asked logger for not registerd provider: " + providerType);
					}
				} else {
					// If no provider config yet, we create log but with asterisk suffix and not remote
					var logger = new Logger().provider(providerType + "*");
					contextInitializer.done(function() {
						var conf = providerConfig(providerType);
						if (conf) {
							// When provider configured (via init()), we set real type and, if required, a remote spooler
							logger.provider(providerType).remoteLog(conf.logEnabled && cometd);
							conf.log = logger.get();
						} else {
							log.warn("Using logger for not registerd provider: " + providerType);
						}
					});
					return logger.get();
				}				
			}
			return new Logger().get(); // default logger: without prefix and remote not enabled  
		};
		
		this.message = message;
		this.showWarn = showWarn;
		this.noticeWarn = noticeWarn;
		this.showError = showError;
		this.noticeError = noticeError;
		this.showInfo = showInfo;
		this.noticeInfo = noticeInfo;
		this.showConfirm = showConfirm
		
		/**
		 * Add style to current document (to the end of head).
		 */
		this.loadStyle = function(cssUrl) {
			if (document.createStyleSheet) {
				document.createStyleSheet(cssUrl); // IE way
			} else {
				if ($("head").find("link[href='"+cssUrl+"']").length == 0) {
					var headElems = document.getElementsByTagName("head");
					var style = document.createElement("link");
					style.type = "text/css";
					style.rel = "stylesheet";
					style.href = cssUrl;
					headElems[headElems.length - 1].appendChild(style);
				} // else, already added
			}
		};
		
		this.initRequest = initRequest; // for use in other modules (providers, portlets etc)
	}
	
	var webConferencing = new WebConferencing();
	
	// Register webConferencing in global eXo namespace (for non AMD uses)
	if (typeof window.eXo === "undefined" || !eXo) {
		window.eXo = {};
	}
	if (typeof eXo.webConferencing === "undefined" || !eXo.webConferencing) {
		eXo.webConferencing = webConferencing;
	} else {
		log.trace("eXo.webConferencing already defined");
	}
	
	$(function() {
		try {
			// Init notification styles
			// configure Pnotify: use jQuery UI css
			$.pnotify.defaults.styling = "jqueryui";
			// no history roller in the right corner
			$.pnotify.defaults.history = false;
			
			// Load common styles here - it's common CSS for all skins so far.
			webConferencing.loadStyle("/webconferencing/skin/jquery-ui.min.css");
			webConferencing.loadStyle("/webconferencing/skin/jquery-ui.structure.min.css");
			webConferencing.loadStyle("/webconferencing/skin/jquery-ui.theme.min.css");
			webConferencing.loadStyle("/webconferencing/skin/jquery.pnotify.default.css");
			webConferencing.loadStyle("/webconferencing/skin/jquery.pnotify.default.icons.css");
			//webConferencing.loadStyle("/webconferencing/skin/webconferencing.css"); // this CSS will be loaded as portlet skin
			// FYI eXo.env.client.skin contains skin name, it can be consulted to load a specific CSS
		} catch(err) {
			log.error("Error configuring Web Conferencing notifications.", err);
		}
	});

	log.trace("< Loaded at " + location.origin + location.pathname);
	
	return webConferencing;
})($, cCometD);
