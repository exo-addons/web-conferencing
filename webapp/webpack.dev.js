const path = require("path");
const merge = require("webpack-merge");
const webpackCommonConfig = require("./webpack.common.js");

// the display name of the war
const app = "webconferencing";

// add the server path to your server location path (it's a symlink in webapp folder to a real server')
const exoServerPath = "exo-server";

let config = merge(webpackCommonConfig, {
	output: {
		path: path.resolve(`${exoServerPath}/webapps/${app}/`)
	},
	devtool: "inline-source-map"
});

module.exports = config;
