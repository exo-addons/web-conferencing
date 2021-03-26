<template>
  <v-app class="VuetifyApp ma-0">
    <div ref="callbutton" :class="['call-button-container']">
      <dropdown
        v-click-outside="hideDropdown"
        v-if="providersButton.length > 1"
        ref="dropdown"
        :positionclass="positionClass"
        :providersbutton="providersButton"
        :isopen="isOpen"
        :header="header"
        @updated="createButtons"
        @getRefs="getRef($event)"
        @showDropdown="showDropdown($event)"
        @dropdownIsVisualized="fireDropdownIsVisualized"
        @selectedProvider="hideDropdown" />
      <singlebtn v-else ref="singlebtn" />
    </div>
  </v-app>
</template>

<script>
import Dropdown from "./Dropdown.vue";
import singlebtn from "./SingleButton.vue";

export default {
  name: "CallButtons",
  components: {
    Dropdown,
    singlebtn
  },
  directives: {
    "click-outside": {
      priority: 700,
      bind: function(el, binding, vnode) {
        if (el.classList.value.includes("dropdown-vue")) { // TODO too general text 'dropdown-vue' to detect exactky our dropdown
          el.clickOutside = function(e) {
            if (!(el === e.target || el.contains(e.target))) {
              vnode.context[binding.expression](e);
            }
          };
          document.body.addEventListener("click", el.clickOutside);
        }
      },
      unbind: function(el) {
        if (el) {
          document.body.removeEventListener("click", el.clickOutside);
        }
      }
    }
  },
  props: {
    language: {
      type: String,
      required: true
    },
    resourceBundleName: {
      type: String,
      required: true
    },
    callContext: {
      type: Object,
      required: true
    }
  },
  data() {
    return {
      providersButton: [],
      error: null,
      isOpen: false,
      isDropdownVisualized: false, // is added to DOM
      childRef: null,
      isFirstInitialization: true,
      log: null
    };
  },
  computed: {
    dropdown: function() {
      return this.$refs.callbutton.getBoundingClientRect();
    },
    dropdownContainer: function() {
      return this.$refs.dropdown.$refs.buttonsContainer.getBoundingClientRect();
    },
    client: function() {
      return document.body.getBoundingClientRect();
    },
    positionClass: function() {
      let position = "right";
      if (this.isDropdownVisualized) {
        const widthRelation =
          (this.client.width - this.dropdown.left) /
          this.dropdownContainer.width;
        if (widthRelation < 1.1) {
          position = "left";
        }
      }
      return position;
    },
    parentContainerElement() {
      return this.$refs.callbutton.parentElement.parentElement.parentElement;
    },
    parentClass() {
      return Object.values(this.parentContainerElement.classList).join("");
    },
    condition() {
      return (
        this.parentClass.includes("call-button-mini") ||
        this.parentClass.includes("call-button--tiptip")
      );
    },
    header() {
      return this.condition
        ? {
            bgHover: "white",
            // paddingClass: "pa-1",
            bgMini: this.isOpen ? "#d3d6db" : "#ffffff"
          }
        : {
            bgHover: this.isOpen ? "var(--allPagesGreyColor, #e1e8ee)" : "white"
            // paddingClass: "px-2"
          };
    }
  },
  watch: {
    callContext(newContext, oldContext) {
      this.setProvidersButtons(newContext);
    }
    // screenWidth(newWidth, oldWidth) {
    //   if (newWidth <= 980) {
    //     console.log("WIIDTH")
    //     // this.parentClass = this.parentClass + "call-button-mini";
    //   }
    // }
  },
  created() {
    this.log = webConferencing.getLog("webconferencing");
  },
  // mounted() {
  //   this.$nextTick(() => {
  //     window.addEventListener("resize", this.onResize);
  //   });
  // },
  methods: {
    // onResize() {
    //   this.screenWidth = window.innerWidth;
    // },
    setProvidersButtons(context) {
      this.providersButton.splice(0);
      this.$refs.callbutton.classList.remove("single");
      this.hideDropdown();
      const thevue = this;
      try {
        if (context && context.details && this.providersButton.length === 0) {
          context.parentClasses = this.parentClass;
          webConferencing.getAllProviders().then(providers => {
            const callButtons = [];
            const callPermissions = [];
            providers.forEach(provider => {
              // If provider is initialized - it is activated for use in the Web Conferencing
              if (provider.isInitialized) {
                // Check if the current user can call to the target context (e.g. has the target permissions to use this provider)
                const permission = context.canCall(provider.getType());
                callPermissions.push(permission);
                permission.then(() => {
                  // User has permissions to use this provider - render the button
                  // By checking here we will invoke only allowed provider (better client perf).
                  callButtons.push(provider.callButton(context));
                }).catch(() => {
                  // User has no permissions to use this privider or an error prevents him from using it - skip the button.
                  // TODO #1 But if we will need to render a button as blocked for a forbidden case,
                  // then mark the button promise here as forbidden and do the rendering below in Promise.allSettled(callButtons).
                  // TODO #2 Alternativelly it could be possible to let a provider implement rendering of the blocked button
                  // by checking context.canCall() in its code - but this way it will be in hands of the provider
                  // to control actual permissions and behavioir may not be the same for all providers as result.
                  //const button = provider.callButton(context);
                  //button.isForbidden = true;
                  //callButtons.push(button);
                });
              }
            });
            Promise.allSettled(callPermissions).then(() => {
              Promise.allSettled(callButtons).then(resCallButtons => {
                resCallButtons.forEach(button => {
                  if (button.status === "fulfilled") {
                    // Provider's button is ready - add it to the container in this.providersButton.
                    // TODO #1 If we need render a button for forbidden user, check isForbidden here
                    // and block the button by a CSS of its container.
                    //if (button.isForbidden) {
                      // apply blocking CSS/JS to the button.value element or Vue comp (detect this via 'instanceof Vue')
                    //}
                    this.providersButton.push(button.value);
                    if (button.value.$data) {
                      button.value.$data.header = "CALL";
                    }
                  }
                });
                thevue.createButtons();
              });
            });
          });
        } else if (context && !context.details) {
          // mini chat - TODO whata a logic for mini chat with context w/o details??
          // TODO Why we need to init if no context or its details?
          // TODO copypasted code - why we need it here??
          if (this.isFirstInitialization) {
            this.isFirstInitialization = false;
            this.log.trace("<< setProvidersButtons() << Call buttons are initialized first time");
            this.fireCreated();
          }
        }
      } catch (err) {
        this.log.error("Error building call buttons", err);
      }
    },
    createButtons() {
      this.log.trace(">> createButtons()");
      let ref;
      let vm = null;
      if (this.providersButton.length !== 0) {
        for (const [index, pb] of this.providersButton.entries()) {
          if (this.providersButton.length > 1) {
            //add buttons to dropdown component
            if (this.isOpen) {
              ref = this.childRef.callbutton[index];
              // add vue button
              if (pb instanceof Vue) {
                vm = pb.$mount(); // TODO why we need vm globaly?
                // vm.$el.innerHTML = "<span class='v-btn__content'><i class='uiIconSocPhone uiIconBlue'></i>Jitsi Call</span>";
                ref.appendChild(vm.$el);
              } else {
                // add button as DOM Element
                ref.appendChild(pb);
              }
            }
          } else {
            //add a single button
            const callButton = this.$refs.callbutton.childNodes[0];
            this.$refs.callbutton.classList.add("single");
            callButton.innerHTML = "";
            if (pb instanceof Vue) {
              // add vue button
              vm = pb.$mount(); // TODO why we need vm globaly?
              callButton.appendChild(vm.$el);
            } else {
              // add button as DOM Element
              callButton.appendChild(pb);
            }
          }
        }
      } else {
        const callButton = this.$refs.callbutton.childNodes[0];
        callButton.innerHTML = "";
      }

      if (this.isFirstInitialization) {
        this.isFirstInitialization = false;
        this.log.trace("<< createButtons() << Call buttons are initialized first time");
        this.fireCreated();
      }
    },
    showDropdown() {
      this.isOpen = !this.isOpen;
    },
    hideDropdown() {
      if (this.isOpen) {
        this.isOpen = false;
        this.isDropdownVisualized = false;
        this.parentContainerElement.blur();
      }
    },
    getRef(ref) {
      this.childRef = ref;
    },
    fireCreated() {
      this.$emit("created");
    },
    fireDropdownIsVisualized() {
      this.isDropdownVisualized = true;
    }
  }
};
</script>

<style lang="less">
@import "../../../skin/less/variables.less";
@import "../../../skin/less/mixins.less";
.VuetifyApp {
  .call-button-container {
    min-width: 64px;
    max-width: 240px;
    &:hover {
      .dropdown-header {
        background-color: var(--allPagesGreyColor, #e1e8ee);
      }
    }
    button {
      .v-btn__content {
        letter-spacing: normal;
        // padding: 0 10px;
      }
    }
    &.single {
      // width: @width - 14px;
      height: 36px;
      border: 1px solid rgb(232, 238, 242);
      border-radius: 3px;
      background-color: var(--allPagesBaseBackground, #ffffff) !important;;
      padding: 0 10px;
      .single-btn-container {
        height: inherit;
        a {
          display: flex;
          height: inherit;
        }
        button {
          [class^="uiIcon"] {
            &::before {
              vertical-align: super;
            }
          }
        }
      }
      &:hover {
        background-color: var(--allPagesGreyColor, #e1e8ee);
        // .single-btn-container {
        background-color: var(--allPagesGreyColor, #e1e8ee);
        // }
        a:hover {
          i {
            color: @primaryColor;
          }
          span {
            color: unset;
          }
        }
      }
    }
    a,
    a:hover,
    a:focus {
      color: var(--allPagesDarkGrey, #4d5466) !important;
      letter-spacing: normal;
    }
    [class^="call-button-container-"] {
      width: 100%;
    }
    cursor: pointer !important;
    position: relative;
    z-index: 100;
    min-height: 36px;
  }
  [class^="call-button-container-"]:hover,
  .room-actions-container {
    .call-button-container {
      a {
        [class^="uiIcon"] {
          &:before {
            color: unset;
            margin-right: 4px;
          }
        }
      }
    }
  }
}
.call-button-mini {
  .VuetifyApp {
    .call-button-container {
      min-width: unset;
      padding: 0 0 7px 0;
      &:hover {
        .dropdown-header {
          background-color: transparent;
        }
      }
      min-height: 10px;
      .dropdown-vue {
        .buttons-container {
          &.left {
            right: -10px;
          }
        }
      }
      &.single {
        padding: 0 10px 0px 0;
        width: unset;
        border: none;
        height: 20px;
        background-color: transparent !important;
        // &.single-btn-container {
        button {
          [class^="uiIcon"] {
            &::before {
              vertical-align: baseline;
            }
          }
        }
        // }
        &:hover {
          background-color: transparent;
          .single-btn-container {
            background-color: transparent;
          }
        }
      }
    }
    &:hover {
      background-color: unset;
      .dropdown-vue {
        background-color: unset;
        .dropdown-header {
          background-color: unset;
          .uiIconMiniArrowDown {
            background: #d3d6db;
            border-radius: 50%;
          }
        }
      }
      &.single {
        border: none;
        width: inherit;
        .single-btn-container {
          width: inherit;
        }
      }
    }

    .buttons-container {
      top: 23px;
    }
  }
}
.call-button-mini.call-button--tiptip {
  .VuetifyApp {
    .call-button-container {
      &.single {
        padding: 0;
        button {
          [class^="uiIcon"] {
            &::before {
              vertical-align: super;
            }
          }
        }
      }
      .dropdown-vue {
        position: relative;
        .buttons-container {
          &.left {
            right: -16px;
          }
        }
        .dropdown-header {
          position: relative;
          .dropdown-heading {
            .uiIconSocPhone {
              vertical-align: unset;
              &::before {
                content: "\e92b";
              }
            }
          }
          .uiIconMiniArrowDown {
            position: absolute;
            right: -16px;
            font-size: 8px !important;
            padding: 4px;
            bottom: 2px;
            border-radius: 50%;
            &::before {
              color: @primaryColor;
            }
          }
        }
      }
      .buttons-container {
        position: absolute;
        top: 28px;
        box-shadow: 1px 1px 10px rgba(0, 0, 0, 0.15);
        [class^="call-button-container-"] {
          text-align: left;
          a,
          a:hover,
          a:focus {
            color: black;
          }
          a:hover {
            i,
            span {
              color: white;
            }
          }
          &:hover {
            i,
            span {
              color: white;
            }
          }
        }
      }
    }
  }
}
.call-button-mini.call-button--chat-drawer {
  margin-right: 6px;
  .call-button-container {
    padding: 0 10px 10px 0;
    &.single {
      padding: 0 10px 10px 0;
    }
  }
}
.space-title-action-components {
  .call-button.call-button--space {
    .call-button-container {
      margin: 0 15px;
      &.single {
        height: unset;
        .single-btn-container {
          .v-btn {
            vertical-align: -webkit-baseline-middle;
          }
        }
      }
    }
  }
}

#UIProfileHeaderContainer {
  position: relative;
  z-index: 100;
}
</style>