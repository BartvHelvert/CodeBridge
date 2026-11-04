// Fails as an extension does when something it needs is missing: activate() throws.
exports.activate = function () {
  throw new Error('The failure probe refuses to activate');
};
