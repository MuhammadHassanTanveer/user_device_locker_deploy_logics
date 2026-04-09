import 'package:flutter/material.dart';

class Dimensions {
  // Font Sizes
  static double fontSizeExtraSmall(BuildContext context) =>
      MediaQuery.of(context).size.width >= 1300 ? 12 : 10;

  static double fontSizeSmall(BuildContext context) =>
      MediaQuery.of(context).size.width >= 1300 ? 14 : 12;

  static double fontSizeDefault(BuildContext context) =>
      MediaQuery.of(context).size.width >= 1300 ? 16 : 14;

  static double fontSizeLarge(BuildContext context) =>
      MediaQuery.of(context).size.width >= 1300 ? 18 : 16;

  static double fontSizeExtraLarge(BuildContext context) =>
      MediaQuery.of(context).size.width >= 1300 ? 20 : 18;

  static double fontSizeOverLarge(BuildContext context) =>
      MediaQuery.of(context).size.width >= 1300 ? 26 : 24;

  static const double paddingSizeExtraSmall = 5.0;
  static const double paddingSizeSmall = 10.0;
  static const double paddingSizeDefault = 15.0;
  static const double paddingSizeLarge = 20.0;
  static const double paddingSizeExtraLarge = 25.0;
  static const double paddingSizeOverLarge = 30.0;
  static const double paddingSizeExtraOverLarge = 35.0;

  static const double radiusSmall = 5.0;
  static const double radiusDefault = 10.0;
  static const double radiusLarge = 15.0;
  static const double radiusExtraLarge = 20.0;

  static const double webMaxWidth = 1170;
  static const int messageInputLength = 250;
}
